/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.druid.storage.google;

import com.google.api.client.http.AbstractInputStreamContent;
import com.google.api.gax.paging.Page;
import com.google.cloud.ReadChannel;
import com.google.cloud.WriteChannel;
import com.google.cloud.storage.Blob;
import com.google.cloud.storage.BlobId;
import com.google.cloud.storage.BlobInfo;
import com.google.cloud.storage.Storage;
import com.google.common.base.Supplier;
import com.google.common.collect.Iterables;
import org.apache.druid.java.util.common.HumanReadableBytes;
import org.apache.druid.java.util.common.IOE;
import org.apache.druid.java.util.common.logger.Logger;

import javax.annotation.Nullable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.Channels;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

public class GoogleStorage
{
  private static final Logger log = new Logger(GoogleStorage.class);
  private static final HumanReadableBytes DEFAULT_WRITE_CHUNK_SIZE = new HumanReadableBytes("4MiB");

  /**
   * Some segment processing tools such as DataSegmentKiller are initialized when an ingestion job starts
   * if the extension is loaded, even when the implementation of DataSegmentKiller is not used. As a result,
   * if we have a Storage instead of a supplier of it, it can cause unnecessary config validation
   * against Google storage even when it's not used at all. To perform the config validation
   * only when it is actually used, we use a supplier.
   * <p>
   * See OmniDataSegmentKiller for how DataSegmentKillers are initialized.
   */
  private final Supplier<Storage> storage;

  public GoogleStorage(final Supplier<Storage> storage)
  {
    this.storage = storage;
  }

  /**
   * Upload an object. From {@link Storage#createFrom(BlobInfo, InputStream, int, Storage.BlobWriteOption...)},
   * "larger buffer sizes might improve the upload performance but require more memory."
   *
   * @param bucket       target bucket
   * @param path         target path
   * @param mediaContent content to upload
   * @param bufferSize   size of upload buffer, or null to use the upstream default (15 MB as of this writing)
   */
  public void insert(
      final String bucket,
      final String path,
      final AbstractInputStreamContent mediaContent,
      @Nullable final Integer bufferSize
  ) throws IOException
  {
    if (bufferSize == null) {
      storage.get().createFrom(getBlobInfo(bucket, path), mediaContent.getInputStream());
    } else {
      storage.get().createFrom(getBlobInfo(bucket, path), mediaContent.getInputStream(), bufferSize);
    }
  }

  public InputStream getInputStream(final String bucket, final String path) throws IOException
  {
    return getInputStream(bucket, path, 0, null, null);
  }

  public InputStream getInputStream(
      final String bucket,
      final String path,
      long start
  ) throws IOException
  {
    return getInputStream(bucket, path, start, null, null);
  }

  public InputStream getInputStream(
      final String bucket,
      final String path,
      long start,
      Long length
  ) throws IOException
  {
    return getInputStream(bucket, path, start, length, null);
  }

  public InputStream getInputStream(
      final String bucket,
      final String path,
      long start,
      @Nullable Long length,
      @Nullable final Integer chunkSize
  )
      throws IOException
  {
    ReadChannel reader = storage.get().reader(bucket, path);
    reader.seek(start);
    if (length != null) {
      reader.limit(start + length);
    }
    if (chunkSize != null) {
      reader.setChunkSize(chunkSize);
    }
    // Using default read buffer size (2 MB)
    return Channels.newInputStream(reader);
  }

  public OutputStream getObjectOutputStream(
      final String bucket,
      final String path,
      @Nullable final Integer chunkSize
  )
  {
    WriteChannel writer = storage.get().writer(getBlobInfo(bucket, path));
    // Limit GCS internal write buffer memory to prevent OOM errors
    writer.setChunkSize(chunkSize == null ? DEFAULT_WRITE_CHUNK_SIZE.getBytesInInt() : chunkSize);

    return Channels.newOutputStream(writer);
  }

  public GoogleStorageObjectMetadata getMetadata(
      final String bucket,
      final String path
  ) throws IOException
  {
    Blob blob = storage.get().get(bucket, path, Storage.BlobGetOption.fields(Storage.BlobField.values()));
    if (blob == null) {
      throw new IOE("Failed to fetch google cloud storage object from bucket [%s] and path[%s].", bucket, path);
    }
    return new GoogleStorageObjectMetadata(
        blob.getBucket(),
        blob.getName(),
        blob.getSize(),
        blob.getUpdateTimeOffsetDateTime()
            .toEpochSecond() * 1000
    );
  }


  /**
   * Deletes an object in a bucket on the specified path
   *
   * A false response from GCS delete API is indicative of file not found. Any other error is raised as a StorageException
   * and should be explicitly handled.
   * Ref: <a href="https://github.com/googleapis/java-storage/blob/v2.29.1/google-cloud-storage/src/main/java/com/google/cloud/storage/spi/v1/HttpStorageRpc.java">HttpStorageRpc.java</a>
   *
   * @param bucket GCS bucket
   * @param path   Object path
   */
  public void delete(final String bucket, final String path)
  {
    if (!storage.get().delete(bucket, path)) {
      log.debug("Google cloud storage object to be deleted not found in bucket [%s] and path [%s].", bucket, path);
    }
  }

  /**
   * Deletes a list of objects in a bucket
   * A false response from GCS delete API is indicative of file not found. Any other error is raised as a StorageException
   * and should be explicitly handled.
   * Ref: <a href="https://github.com/googleapis/java-storage/blob/v2.29.1/google-cloud-storage/src/main/java/com/google/cloud/storage/spi/v1/HttpStorageRpc.java">HttpStorageRpc.java</a>
   *
   * @param bucket GCS bucket
   * @param paths  Iterable for absolute paths of objects to be deleted inside the bucket
   */
  public void batchDelete(final String bucket, final Iterable<String> paths)
  {
    final List<Boolean> statuses = storage.get().delete(Iterables.transform(paths, input -> BlobId.of(bucket, input)));
    if (statuses.contains(false)) {
      log.debug(
          "Google cloud storage object(s) to be deleted not found in bucket [%s].",
          bucket
      );
    }
  }

  public boolean exists(final String bucket, final String path)
  {
    Blob blob = storage.get().get(bucket, path);
    return blob != null;
  }

  public long size(final String bucket, final String path) throws IOException
  {
    Blob blob = storage.get().get(bucket, path, Storage.BlobGetOption.fields(Storage.BlobField.SIZE));
    if (blob == null) {
      throw new IOE("Failed to fetch google cloud storage object from bucket [%s] and path [%s].", bucket, path);
    }
    return blob.getSize();
  }

  /**
   * Return the etag for an object. This is a value that changes whenever the object's data or metadata changes and is
   * typically but not always the MD5 hash of the object. Ref:
   * <a href="https://cloud.google.com/storage/docs/hashes-etags#etags">ETags</a>
   *
   * @param bucket
   * @param path
   *
   * @return
   *
   * @throws IOException
   */
  public String version(final String bucket, final String path) throws IOException
  {
    Blob blob = storage.get().get(bucket, path, Storage.BlobGetOption.fields(Storage.BlobField.ETAG));
    if (blob == null) {
      throw new IOE("Failed to fetch google cloud storage object from bucket [%s] and path [%s].", bucket, path);
    }
    return blob.getEtag();
  }

  /***
   * Provides a paged listing of objects for a given bucket and prefix
   * @param bucket GCS bucket
   * @param prefix Path prefix
   * @param pageSize Number of objects per page
   * @param pageToken Continuation token for the next page; use null for the first page
   *                  or the nextPageToken from the previous {@link GoogleStorageObjectPage}
   */
  public GoogleStorageObjectPage list(
      final String bucket,
      @Nullable final String prefix,
      @Nullable final Long pageSize,
      @Nullable final String pageToken
  ) throws IOException
  {
    List<Storage.BlobListOption> options = new ArrayList<>();

    if (prefix != null) {
      options.add(Storage.BlobListOption.prefix(prefix));
    }

    if (pageSize != null) {
      options.add(Storage.BlobListOption.pageSize(pageSize));
    }

    if (pageToken != null) {
      options.add(Storage.BlobListOption.pageToken(pageToken));
    }

    Page<Blob> blobPage = storage.get().list(bucket, options.toArray(new Storage.BlobListOption[0]));

    if (blobPage == null) {
      throw new IOE("Failed to fetch google cloud storage object from bucket [%s] and prefix [%s].", bucket, prefix);
    }


    List<GoogleStorageObjectMetadata> googleStorageObjectMetadataList =
        blobPage.streamValues()
                .map(blob -> new GoogleStorageObjectMetadata(
                    blob.getBucket(),
                    blob.getName(),
                    blob.getSize(),
                    blob.getUpdateTimeOffsetDateTime()
                        .toEpochSecond() * 1000
                ))
                .collect(Collectors.toList());

    return new GoogleStorageObjectPage(googleStorageObjectMetadataList, blobPage.getNextPageToken());

  }


  private BlobInfo getBlobInfo(final String bucket, final String path)
  {
    BlobId blobId = BlobId.of(bucket, path);
    return BlobInfo.newBuilder(blobId).build();
  }
}
