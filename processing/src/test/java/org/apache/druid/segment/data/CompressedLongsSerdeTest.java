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

package org.apache.druid.segment.data;

import com.google.common.base.Supplier;
import com.google.common.primitives.Longs;
import it.unimi.dsi.fastutil.ints.IntArrays;
import org.apache.druid.java.util.common.StringUtils;
import org.apache.druid.java.util.common.io.smoosh.FileSmoosher;
import org.apache.druid.java.util.common.io.smoosh.SmooshedFileMapper;
import org.apache.druid.java.util.common.io.smoosh.SmooshedWriter;
import org.apache.druid.segment.writeout.OffHeapMemorySegmentWriteOutMedium;
import org.apache.druid.segment.writeout.SegmentWriteOutMedium;
import org.apache.druid.segment.writeout.TmpFileSegmentWriteOutMediumFactory;
import org.apache.druid.utils.CloseableUtils;
import org.hamcrest.CoreMatchers;
import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.junit.Assert;
import org.junit.Assume;
import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.Channels;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

@RunWith(Parameterized.class)
public class CompressedLongsSerdeTest
{
  @Parameterized.Parameters(name = "{0} {1} {2}")
  public static Iterable<Object[]> compressionStrategies()
  {
    List<Object[]> data = new ArrayList<>();
    for (CompressionFactory.LongEncodingStrategy encodingStrategy : CompressionFactory.LongEncodingStrategy.values()) {
      for (CompressionStrategy strategy : CompressionStrategy.values()) {
        data.add(new Object[]{encodingStrategy, strategy, ByteOrder.BIG_ENDIAN});
        data.add(new Object[]{encodingStrategy, strategy, ByteOrder.LITTLE_ENDIAN});
      }
    }
    return data;
  }

  @Rule
  public TemporaryFolder temporaryFolder = new TemporaryFolder();

  @Rule
  public ExpectedException expectedException = ExpectedException.none();

  protected final CompressionFactory.LongEncodingStrategy encodingStrategy;
  protected final CompressionStrategy compressionStrategy;
  protected final ByteOrder order;

  private final long[] values0 = {};
  private final long[] values1 = {0, 1, 1, 0, 1, 1, 1, 1, 0, 0, 1, 1};
  private final long[] values2 = {12, 5, 2, 9, 3, 2, 5, 1, 0, 6, 13, 10, 15};
  private final long[] values3 = {1, 1, 1, 1, 1, 11, 11, 11, 11};
  private final long[] values4 = {200, 200, 200, 401, 200, 301, 200, 200, 200, 404, 200, 200, 200, 200};
  private final long[] values5 = {123, 632, 12, 39, 536, 0, 1023, 52, 777, 526, 214, 562, 823, 346};
  private final long[] values6 = {1000000, 1000001, 1000002, 1000003, 1000004, 1000005, 1000006, 1000007, 1000008};
  private final long[] values7 = {
      Long.MAX_VALUE, Long.MIN_VALUE, 12378, -12718243, -1236213, 12743153, 21364375452L,
      65487435436632L, -43734526234564L
  };
  private final long[] values8 = {Long.MAX_VALUE, 0, 321, 15248425, 13523212136L, 63822, 3426, 96};

  // built test value with enough unique values to not use table encoding for auto strategy
  private static long[] addUniques(long[] val)
  {
    long[] ret = new long[val.length + CompressionFactory.MAX_TABLE_SIZE];
    for (int i = 0; i < CompressionFactory.MAX_TABLE_SIZE; i++) {
      ret[i] = i;
    }
    System.arraycopy(val, 0, ret, 256, val.length);
    return ret;
  }

  public CompressedLongsSerdeTest(
      CompressionFactory.LongEncodingStrategy encodingStrategy,
      CompressionStrategy compressionStrategy,
      ByteOrder order
  )
  {
    this.encodingStrategy = encodingStrategy;
    this.compressionStrategy = compressionStrategy;
    this.order = order;
  }

  @Test
  public void testValueSerde() throws Exception
  {
    testWithValues(values0);
    testWithValues(values1);
    testWithValues(values2);
    testWithValues(values3);
    testWithValues(values4);
    testWithValues(values5);
    testWithValues(values6);
    testWithValues(values7);
    testWithValues(values8);
  }

  @Test
  public void testChunkSerde() throws Exception
  {
    long[] chunk = new long[10000];
    for (int i = 0; i < 10000; i++) {
      chunk[i] = i;
    }
    testWithValues(chunk);
  }

  // this test takes ~50 minutes to run (even skipping 'auto')
  @Ignore
  @Test
  public void testTooManyValues() throws IOException
  {
    // uncomment this if 'auto' encoded long unbounded heap usage gets put in check and this can actually pass
    if (encodingStrategy.equals(CompressionFactory.LongEncodingStrategy.AUTO)) {
      return;
    }
    expectedException.expect(ColumnCapacityExceededException.class);
    expectedException.expectMessage(ColumnCapacityExceededException.formatMessage("test"));
    try (
        SegmentWriteOutMedium segmentWriteOutMedium =
            TmpFileSegmentWriteOutMediumFactory.instance().makeSegmentWriteOutMedium(temporaryFolder.newFolder())
    ) {
      ColumnarLongsSerializer serializer = CompressionFactory.getLongSerializer(
          "test",
          segmentWriteOutMedium,
          "test",
          order,
          encodingStrategy,
          compressionStrategy,
          segmentWriteOutMedium.getCloser()
      );
      serializer.open();

      final long numRows = Integer.MAX_VALUE + 100L;
      for (long i = 0L; i < numRows; i++) {
        serializer.add(ThreadLocalRandom.current().nextLong());
      }
    }
  }

  @Test
  public void testLargeColumn() throws IOException
  {
    // This test only makes sense if we can use BlockLayoutColumnarLongsSerializer directly. Exclude incompatible
    // combinations of compressionStrategy, encodingStrategy.
    Assume.assumeThat(compressionStrategy, CoreMatchers.not(CoreMatchers.equalTo(CompressionStrategy.NONE)));
    Assume.assumeThat(encodingStrategy, CoreMatchers.equalTo(CompressionFactory.LongEncodingStrategy.LONGS));

    final File columnDir = temporaryFolder.newFolder();
    final String columnName = "column";
    final long numRows = 500_000; // enough values that we expect to switch into large-column mode

    try (
        SegmentWriteOutMedium segmentWriteOutMedium =
            TmpFileSegmentWriteOutMediumFactory.instance().makeSegmentWriteOutMedium(temporaryFolder.newFolder());
        FileSmoosher smoosher = new FileSmoosher(columnDir)
    ) {
      final Random random = new Random(0);
      final int fileSizeLimit = 128_000; // limit to 128KB so we switch to large-column mode sooner
      final ColumnarLongsSerializer serializer = new BlockLayoutColumnarLongsSerializer(
          columnName,
          segmentWriteOutMedium,
          columnName,
          order,
          new LongsLongEncodingWriter(order),
          compressionStrategy,
          fileSizeLimit,
          segmentWriteOutMedium.getCloser()
      );
      serializer.open();

      for (int i = 0; i < numRows; i++) {
        serializer.add(random.nextLong());
      }

      try (SmooshedWriter primaryWriter = smoosher.addWithSmooshedWriter(columnName, serializer.getSerializedSize())) {
        serializer.writeTo(primaryWriter, smoosher);
      }
    }

    try (SmooshedFileMapper smooshMapper = SmooshedFileMapper.load(columnDir)) {
      MatcherAssert.assertThat(
          "Number of value parts written", // ensure the column actually ended up multi-part
          smooshMapper.getInternalFilenames().stream().filter(s -> s.startsWith("column_value_")).count(),
          Matchers.greaterThan(1L)
      );

      final CompressedColumnarLongsSupplier columnSupplier = CompressedColumnarLongsSupplier.fromByteBuffer(
          smooshMapper.mapFile(columnName),
          order,
          smooshMapper
      );

      try (final ColumnarLongs column = columnSupplier.get()) {
        Assert.assertEquals(numRows, column.size());
      }
    }
  }

  public void testWithValues(long[] values) throws Exception
  {
    testValues(values);
    testValues(addUniques(values));
  }

  public void testValues(long[] values) throws Exception
  {
    SegmentWriteOutMedium segmentWriteOutMedium = new OffHeapMemorySegmentWriteOutMedium();
    ColumnarLongsSerializer serializer = CompressionFactory.getLongSerializer(
        "test",
        segmentWriteOutMedium,
        "test",
        order,
        encodingStrategy,
        compressionStrategy,
        segmentWriteOutMedium.getCloser()
    );
    serializer.open();

    serializer.addAll(values, 0, values.length);
    Assert.assertEquals(values.length, serializer.size());

    final ByteArrayOutputStream baos = new ByteArrayOutputStream();
    serializer.writeTo(Channels.newChannel(baos), null);
    Assert.assertEquals(baos.size(), serializer.getSerializedSize());
    CompressedColumnarLongsSupplier supplier = CompressedColumnarLongsSupplier
        .fromByteBuffer(ByteBuffer.wrap(baos.toByteArray()), order, null);
    try (ColumnarLongs longs = supplier.get()) {

      assertIndexMatchesVals(longs, values);
      for (int i = 0; i < 10; i++) {
        int a = (int) (ThreadLocalRandom.current().nextDouble() * values.length);
        int b = (int) (ThreadLocalRandom.current().nextDouble() * values.length);
        int start = a < b ? a : b;
        int end = a < b ? b : a;
        tryFill(longs, values, start, end - start);
      }
      testSupplierSerde(supplier, values);
      testConcurrentThreadReads(supplier, longs, values);
    }
    finally {
      segmentWriteOutMedium.close();
    }
  }

  private void tryFill(ColumnarLongs indexed, long[] vals, final int startIndex, final int size)
  {
    long[] filled = new long[size];
    indexed.get(filled, startIndex, size);

    for (int i = startIndex; i < filled.length; i++) {
      Assert.assertEquals(vals[i + startIndex], filled[i]);
    }
  }

  private void assertIndexMatchesVals(ColumnarLongs indexed, long[] vals)
  {
    Assert.assertEquals(vals.length, indexed.size());

    // sequential access
    long[] vector = new long[256];
    int[] indices = new int[vals.length];
    for (int i = 0; i < indexed.size(); ++i) {
      if (i % 256 == 0) {
        indexed.get(vector, i, Math.min(256, indexed.size() - i));
      }
      Assert.assertEquals(vals[i], indexed.get(i));
      Assert.assertEquals(vals[i], vector[i % 256]);
      indices[i] = i;
    }


    // random access, limited to 1000 elements for large lists (every element would take too long)
    IntArrays.shuffle(indices, ThreadLocalRandom.current());
    final int limit = Math.min(indexed.size(), 1000);
    for (int i = 0; i < limit; ++i) {
      int k = indices[i];
      Assert.assertEquals(vals[k], indexed.get(k));
    }
  }

  private void testSupplierSerde(CompressedColumnarLongsSupplier supplier, long[] vals) throws IOException
  {
    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    supplier.writeTo(Channels.newChannel(baos), null);

    final byte[] bytes = baos.toByteArray();
    Assert.assertEquals(supplier.getSerializedSize(), bytes.length);
    CompressedColumnarLongsSupplier anotherSupplier =
        CompressedColumnarLongsSupplier.fromByteBuffer(ByteBuffer.wrap(bytes), order, null);
    try (ColumnarLongs indexed = anotherSupplier.get()) {
      assertIndexMatchesVals(indexed, vals);
    }
  }

  // This test attempts to cause a race condition with the DirectByteBuffers, it's non-deterministic in causing it,
  // which sucks but I can't think of a way to deterministically cause it...
  private void testConcurrentThreadReads(
      final Supplier<ColumnarLongs> supplier,
      final ColumnarLongs indexed, final long[] vals
  ) throws Exception
  {
    final AtomicReference<String> reason = new AtomicReference<>("none");

    final int numRuns = 1000;
    final CountDownLatch startLatch = new CountDownLatch(1);
    final CountDownLatch stopLatch = new CountDownLatch(2);
    final AtomicBoolean failureHappened = new AtomicBoolean(false);
    new Thread(new Runnable()
    {
      @Override
      public void run()
      {
        try {
          startLatch.await();
        }
        catch (InterruptedException e) {
          failureHappened.set(true);
          reason.set("interrupt.");
          stopLatch.countDown();
          return;
        }

        try {
          for (int i = 0; i < numRuns; ++i) {
            for (int j = 0; j < indexed.size(); ++j) {
              final long val = vals[j];
              final long indexedVal = indexed.get(j);
              if (Longs.compare(val, indexedVal) != 0) {
                failureHappened.set(true);
                reason.set(StringUtils.format("Thread1[%d]: %d != %d", j, val, indexedVal));
                stopLatch.countDown();
                return;
              }
            }
          }
        }
        catch (Exception e) {
          e.printStackTrace();
          failureHappened.set(true);
          reason.set(e.getMessage());
        }

        stopLatch.countDown();
      }
    }).start();

    final ColumnarLongs indexed2 = supplier.get();
    try {
      new Thread(new Runnable()
      {
        @Override
        public void run()
        {
          try {
            startLatch.await();
          }
          catch (InterruptedException e) {
            stopLatch.countDown();
            return;
          }

          try {
            for (int i = 0; i < numRuns; ++i) {
              for (int j = indexed2.size() - 1; j >= 0; --j) {
                final long val = vals[j];
                final long indexedVal = indexed2.get(j);
                if (Longs.compare(val, indexedVal) != 0) {
                  failureHappened.set(true);
                  reason.set(StringUtils.format("Thread2[%d]: %d != %d", j, val, indexedVal));
                  stopLatch.countDown();
                  return;
                }
              }
            }
          }
          catch (Exception e) {
            e.printStackTrace();
            reason.set(e.getMessage());
            failureHappened.set(true);
          }

          stopLatch.countDown();
        }
      }).start();

      startLatch.countDown();

      stopLatch.await();
    }
    finally {
      CloseableUtils.closeAndWrapExceptions(indexed2);
    }

    if (failureHappened.get()) {
      Assert.fail("Failure happened.  Reason: " + reason.get());
    }
  }
}
