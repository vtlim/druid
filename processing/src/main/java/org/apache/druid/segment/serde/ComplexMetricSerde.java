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

package org.apache.druid.segment.serde;

import com.google.common.base.Function;
import it.unimi.dsi.fastutil.bytes.ByteArrays;
import org.apache.druid.guice.annotations.ExtensionPoint;
import org.apache.druid.segment.GenericColumnSerializer;
import org.apache.druid.segment.IndexSpec;
import org.apache.druid.segment.column.ColumnBuilder;
import org.apache.druid.segment.column.ColumnConfig;
import org.apache.druid.segment.column.ColumnType;
import org.apache.druid.segment.column.ObjectStrategyComplexTypeStrategy;
import org.apache.druid.segment.column.TypeStrategy;
import org.apache.druid.segment.data.CompressionStrategy;
import org.apache.druid.segment.data.GenericIndexed;
import org.apache.druid.segment.data.ObjectStrategy;
import org.apache.druid.segment.writeout.SegmentWriteOutMedium;

import javax.annotation.Nullable;
import java.nio.ByteBuffer;

/**
 */
@ExtensionPoint
public abstract class ComplexMetricSerde
{
  public abstract String getTypeName();

  public abstract ComplexMetricExtractor getExtractor();

  /**
   * This is deprecated because its usage is going to be removed from the code.
   * <p>
   * It was introduced before deserializeColumn() existed.  This method creates the assumption that Druid knows
   * how to interpret the actual column representation of the data, but I would much prefer that the ComplexMetricSerde
   * objects be in charge of creating and interpreting the whole column, which is what deserializeColumn lets
   * them do.
   *
   * @return an ObjectStrategy as used by GenericIndexed
   */
  @Deprecated
  public abstract ObjectStrategy getObjectStrategy();

  /**
   * Get a {@link TypeStrategy} to assist with writing individual complex values to a {@link ByteBuffer}.
   *
   * @see TypeStrategy
   */
  public <T extends Comparable<T>> TypeStrategy<T> getTypeStrategy()
  {
    return new ObjectStrategyComplexTypeStrategy<>(getObjectStrategy(), ColumnType.ofComplex(getTypeName()));
  }

  /**
   * Returns a function that can convert the Object provided by the ComplexColumn created through deserializeColumn
   * into a number of expected input bytes to produce that object.
   * <p>
   * This is used to approximate the size of the input data via the SegmentMetadataQuery and does not need to be
   * overridden if you do not care about the query.
   *
   * @return A function that can compute the size of the complex object or null if you cannot/do not want to compute it
   */
  @Nullable
  public Function<Object, Long> inputSizeFn()
  {
    return null;
  }

  /**
   * Converts intermediate representation of aggregate to byte[].
   *
   * @param val intermediate representation of aggregate
   *
   * @return serialized intermediate representation of aggregate in byte[]
   */
  public byte[] toBytes(@Nullable Object val)
  {
    if (val != null) {
      byte[] bytes = getObjectStrategy().toBytes(val);
      return bytes != null ? bytes : ByteArrays.EMPTY_ARRAY;
    } else {
      return ByteArrays.EMPTY_ARRAY;
    }
  }

  /**
   * Converts byte[] to intermediate representation of the aggregate.
   *
   * @param data     array
   * @param start    offset in the byte array where to start reading
   * @param numBytes number of bytes to read in given array
   *
   * @return intermediate representation of the aggregate
   */
  public Object fromBytes(byte[] data, int start, int numBytes)
  {
    ByteBuffer bb = ByteBuffer.wrap(data);
    if (start > 0) {
      bb.position(start);
    }
    return getObjectStrategy().fromByteBuffer(bb, numBytes);
  }

  /**
   * Deserializes a ByteBuffer and adds it to the ColumnBuilder.  This method allows for the ComplexMetricSerde
   * to implement it's own versioning scheme to allow for changes of binary format in a forward-compatible manner.
   *
   * @param buffer  the buffer to deserialize
   * @param builder ColumnBuilder to add the column to
   * @param columnConfig ColumnConfiguration used during deserialization
   */
  public void deserializeColumn(
      ByteBuffer buffer,
      ColumnBuilder builder,
      ColumnConfig columnConfig
  )
  {
    deserializeColumn(buffer, builder);
  }


  /**
   * {@link ComplexMetricSerde#deserializeColumn(ByteBuffer, ColumnBuilder, ColumnConfig)} should be used instead of this.
   * This method is left for backward compatibility.
   */
  @Deprecated
  public void deserializeColumn(ByteBuffer buffer, ColumnBuilder builder)
  {
    // default implementation to match default serializer implementation
    final int position = buffer.position();
    final byte version = buffer.get();
    if (version == CompressedComplexColumnSerializer.IS_COMPRESSED) {
      CompressedComplexColumnSupplier supplier = CompressedComplexColumnSupplier.read(
          buffer,
          builder,
          getTypeName(),
          getObjectStrategy()
      );
      builder.setComplexColumnSupplier(supplier);
      builder.setNullValueIndexSupplier(supplier.getNullValues());
      builder.setHasNulls(!supplier.getNullValues().isEmpty());
    } else {
      buffer.position(position);
      builder.setComplexColumnSupplier(
          new ComplexColumnPartSupplier(
              getTypeName(),
              GenericIndexed.read(buffer, getObjectStrategy(), builder.getFileMapper())
          )
      );
    }
  }

  /**
   * {@link ComplexMetricSerde#getSerializer(SegmentWriteOutMedium, String, IndexSpec)} should be used instead of this.
   * This method is left for backward compatibility.
   */
  @Nullable
  @Deprecated
  public GenericColumnSerializer getSerializer(SegmentWriteOutMedium segmentWriteOutMedium, String column)
  {
    return null;
  }

  /**
   * This method provides the ability for a ComplexMetricSerde to control its own serialization.
   * Default implementation uses {@link CompressedComplexColumnSerializer} if {@link IndexSpec#complexMetricCompression}
   * is not null or uncompressed/none, or {@link LargeColumnSupportedComplexColumnSerializer} if no compression is
   * specified.
   *
   * @return an instance of {@link GenericColumnSerializer} used for serialization.
   */
  public GenericColumnSerializer getSerializer(
      SegmentWriteOutMedium segmentWriteOutMedium,
      String column,
      IndexSpec indexSpec
  )
  {
    // backwards compatibility, if defined use it
    final GenericColumnSerializer serializer = getSerializer(segmentWriteOutMedium, column);
    if (serializer != null) {
      return serializer;
    }

    // otherwise, use compressed or generic indexed based serializer
    CompressionStrategy strategy = indexSpec.getComplexMetricCompression();
    if (strategy == null || CompressionStrategy.NONE == strategy || CompressionStrategy.UNCOMPRESSED == strategy) {
      return LargeColumnSupportedComplexColumnSerializer.create(
          segmentWriteOutMedium,
          column,
          getObjectStrategy()
      );
    } else {
      return CompressedComplexColumnSerializer.create(
          segmentWriteOutMedium,
          column,
          indexSpec,
          getObjectStrategy()
      );
    }
  }
}
