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

package org.apache.druid.segment.nested;

import com.google.common.base.Supplier;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import it.unimi.dsi.fastutil.ints.IntIntPair;
import it.unimi.dsi.fastutil.ints.IntIterator;
import it.unimi.dsi.fastutil.longs.LongArraySet;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongSet;
import org.apache.druid.annotations.SuppressFBWarnings;
import org.apache.druid.collections.bitmap.BitmapFactory;
import org.apache.druid.collections.bitmap.ImmutableBitmap;
import org.apache.druid.common.guava.GuavaUtils;
import org.apache.druid.java.util.common.RE;
import org.apache.druid.java.util.common.StringUtils;
import org.apache.druid.java.util.common.io.smoosh.SmooshedFileMapper;
import org.apache.druid.math.expr.ExprEval;
import org.apache.druid.math.expr.ExpressionType;
import org.apache.druid.query.BitmapResultFactory;
import org.apache.druid.query.filter.DruidLongPredicate;
import org.apache.druid.query.filter.DruidPredicateFactory;
import org.apache.druid.segment.DimensionHandlerUtils;
import org.apache.druid.segment.IntListUtils;
import org.apache.druid.segment.column.ColumnBuilder;
import org.apache.druid.segment.column.ColumnConfig;
import org.apache.druid.segment.column.ColumnIndexSupplier;
import org.apache.druid.segment.column.ColumnType;
import org.apache.druid.segment.column.TypeSignature;
import org.apache.druid.segment.column.ValueType;
import org.apache.druid.segment.data.BitmapSerdeFactory;
import org.apache.druid.segment.data.ColumnarInts;
import org.apache.druid.segment.data.ColumnarLongs;
import org.apache.druid.segment.data.CompressedColumnarLongsSupplier;
import org.apache.druid.segment.data.CompressedVSizeColumnarIntsSupplier;
import org.apache.druid.segment.data.FixedIndexed;
import org.apache.druid.segment.data.GenericIndexed;
import org.apache.druid.segment.data.VByte;
import org.apache.druid.segment.index.AllFalseBitmapColumnIndex;
import org.apache.druid.segment.index.BitmapColumnIndex;
import org.apache.druid.segment.index.DictionaryRangeScanningBitmapIndex;
import org.apache.druid.segment.index.DictionaryScanningBitmapIndex;
import org.apache.druid.segment.index.SimpleBitmapColumnIndex;
import org.apache.druid.segment.index.SimpleImmutableBitmapDelegatingIterableIndex;
import org.apache.druid.segment.index.SimpleImmutableBitmapIndex;
import org.apache.druid.segment.index.semantic.DictionaryEncodedStringValueIndex;
import org.apache.druid.segment.index.semantic.DictionaryEncodedValueIndex;
import org.apache.druid.segment.index.semantic.DruidPredicateIndexes;
import org.apache.druid.segment.index.semantic.NullValueIndex;
import org.apache.druid.segment.index.semantic.NumericRangeIndexes;
import org.apache.druid.segment.index.semantic.StringValueSetIndexes;
import org.apache.druid.segment.index.semantic.ValueIndexes;
import org.apache.druid.segment.index.semantic.ValueSetIndexes;
import org.apache.druid.segment.serde.ColumnSerializerUtils;
import org.apache.druid.segment.serde.NestedCommonFormatColumnPartSerde;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.SortedSet;

public class ScalarLongColumnAndIndexSupplier implements Supplier<NestedCommonFormatColumn>, ColumnIndexSupplier
{
  public static ScalarLongColumnAndIndexSupplier read(
      ByteOrder byteOrder,
      BitmapSerdeFactory bitmapSerdeFactory,
      ByteBuffer bb,
      ColumnBuilder columnBuilder,
      ColumnConfig columnConfig,
      @Nullable ScalarLongColumnAndIndexSupplier parent
  )
  {
    final byte version = bb.get();
    final int columnNameLength = VByte.readInt(bb);
    final String columnName = StringUtils.fromUtf8(bb, columnNameLength);

    if (version == NestedCommonFormatColumnSerializer.V0) {
      try {

        final SmooshedFileMapper mapper = columnBuilder.getFileMapper();

        final ByteBuffer encodedValuesBuffer = NestedCommonFormatColumnPartSerde.loadInternalFile(
            mapper,
            columnName,
            ColumnSerializerUtils.ENCODED_VALUE_COLUMN_FILE_NAME
        );
        final ByteBuffer longsValueColumn = NestedCommonFormatColumnPartSerde.loadInternalFile(
            mapper,
            columnName,
            ColumnSerializerUtils.LONG_VALUE_COLUMN_FILE_NAME
        );
        final ByteBuffer valueIndexBuffer = NestedCommonFormatColumnPartSerde.loadInternalFile(
            mapper,
            columnName,
            ColumnSerializerUtils.BITMAP_INDEX_FILE_NAME
        );
        GenericIndexed<ImmutableBitmap> rBitmaps = GenericIndexed.read(
            valueIndexBuffer,
            bitmapSerdeFactory.getObjectStrategy(),
            columnBuilder.getFileMapper()
        );


        final Supplier<FixedIndexed<Long>> longDictionarySupplier;
        if (parent != null) {
          longDictionarySupplier = parent.longDictionarySupplier;
        } else {
          final ByteBuffer longDictionaryBuffer = NestedCommonFormatColumnPartSerde.loadInternalFile(
              mapper,
              columnName,
              ColumnSerializerUtils.LONG_DICTIONARY_FILE_NAME
          );
          longDictionarySupplier = FixedIndexed.read(
              longDictionaryBuffer,
              ColumnType.LONG.getStrategy(),
              byteOrder,
              Long.BYTES
          );
        }

        final CompressedVSizeColumnarIntsSupplier encodedCol = CompressedVSizeColumnarIntsSupplier.fromByteBuffer(
            encodedValuesBuffer,
            byteOrder,
            columnBuilder.getFileMapper()
        );

        final Supplier<ColumnarLongs> longs = CompressedColumnarLongsSupplier.fromByteBuffer(
            longsValueColumn,
            byteOrder,
            columnBuilder.getFileMapper()
        );
        return new ScalarLongColumnAndIndexSupplier(
            longDictionarySupplier,
            encodedCol,
            longs,
            rBitmaps,
            bitmapSerdeFactory.getBitmapFactory(),
            columnConfig
        );
      }
      catch (IOException ex) {
        throw new RE(ex, "Failed to deserialize V%s column.", version);
      }
    } else {
      throw new RE("Unknown version " + version);
    }
  }

  private final Supplier<FixedIndexed<Long>> longDictionarySupplier;

  private final Supplier<ColumnarInts> encodedValuesSupplier;
  private final Supplier<ColumnarLongs> valueColumnSupplier;

  private final GenericIndexed<ImmutableBitmap> valueIndexes;

  private final BitmapFactory bitmapFactory;

  private final ImmutableBitmap nullValueBitmap;
  private final ColumnConfig columnConfig;

  private ScalarLongColumnAndIndexSupplier(
      Supplier<FixedIndexed<Long>> longDictionarySupplier,
      Supplier<ColumnarInts> encodedValuesSupplier,
      Supplier<ColumnarLongs> valueColumnSupplier,
      GenericIndexed<ImmutableBitmap> valueIndexes,
      BitmapFactory bitmapFactory,
      ColumnConfig columnConfig
  )
  {
    this.longDictionarySupplier = longDictionarySupplier;
    this.encodedValuesSupplier = encodedValuesSupplier;
    this.valueColumnSupplier = valueColumnSupplier;
    this.valueIndexes = valueIndexes;
    this.bitmapFactory = bitmapFactory;
    this.nullValueBitmap = valueIndexes.get(0) == null ? bitmapFactory.makeEmptyImmutableBitmap() : valueIndexes.get(0);
    this.columnConfig = columnConfig;
  }

  @Override
  public NestedCommonFormatColumn get()
  {
    return new ScalarLongColumn(
        longDictionarySupplier.get(),
        encodedValuesSupplier,
        valueColumnSupplier.get(),
        nullValueBitmap,
        bitmapFactory
    );
  }

  @Nullable
  @Override
  public <T> T as(Class<T> clazz)
  {
    if (clazz.equals(NullValueIndex.class)) {
      final BitmapColumnIndex nullIndex = new SimpleImmutableBitmapIndex(nullValueBitmap);
      return (T) (NullValueIndex) () -> nullIndex;
    } else if (clazz.equals(ValueIndexes.class)) {
      return (T) new LongValueIndexes();
    } else if (clazz.equals(ValueSetIndexes.class)) {
      return (T) new LongValueSetIndexes();
    } else if (clazz.equals(StringValueSetIndexes.class)) {
      return (T) new LongStringValueSetIndexes();
    } else if (clazz.equals(NumericRangeIndexes.class)) {
      return (T) new LongNumericRangeIndex();
    } else if (clazz.equals(DruidPredicateIndexes.class)) {
      return (T) new LongPredicateIndexes();
    } else if (
        clazz.equals(DictionaryEncodedStringValueIndex.class) ||
        clazz.equals(DictionaryEncodedValueIndex.class)
    ) {
      return (T) new LongDictionaryEncodedValueSetIndex();
    }

    return null;
  }

  private ImmutableBitmap getBitmap(int idx)
  {
    if (idx < 0) {
      return bitmapFactory.makeEmptyImmutableBitmap();
    }

    final ImmutableBitmap bitmap = valueIndexes.get(idx);
    return bitmap == null ? bitmapFactory.makeEmptyImmutableBitmap() : bitmap;
  }

  private class LongValueIndexes implements ValueIndexes
  {
    @Nullable
    @Override
    public BitmapColumnIndex forValue(@Nonnull Object value, TypeSignature<ValueType> valueType)
    {
      final ExprEval<?> eval = ExprEval.ofType(ExpressionType.fromColumnTypeStrict(valueType), value);
      final ExprEval<?> castForComparison = ExprEval.castForEqualityComparison(eval, ExpressionType.LONG);
      if (castForComparison == null) {
        return new AllFalseBitmapColumnIndex(bitmapFactory, nullValueBitmap);
      }
      final long longValue = castForComparison.asLong();

      return new SimpleBitmapColumnIndex()
      {
        final FixedIndexed<Long> dictionary = longDictionarySupplier.get();

        @Override
        public int estimatedComputeCost()
        {
          return 1;
        }

        @Override
        public <T> T computeBitmapResult(BitmapResultFactory<T> bitmapResultFactory, boolean includeUnknown)
        {
          final int id = dictionary.indexOf(longValue);
          if (includeUnknown) {
            if (id < 0) {
              return bitmapResultFactory.wrapDimensionValue(nullValueBitmap);
            }
            return bitmapResultFactory.unionDimensionValueBitmaps(
                ImmutableList.of(getBitmap(id), nullValueBitmap)
            );
          }
          if (id < 0) {
            return bitmapResultFactory.wrapDimensionValue(bitmapFactory.makeEmptyImmutableBitmap());
          }
          return bitmapResultFactory.wrapDimensionValue(getBitmap(id));
        }
      };
    }
  }

  private final class LongValueSetIndexes implements ValueSetIndexes
  {
    final FixedIndexed<Long> dictionary = longDictionarySupplier.get();

    @Nullable
    @Override
    public BitmapColumnIndex forSortedValues(@Nonnull List<?> sortedValues, TypeSignature<ValueType> matchValueType)
    {
      if (sortedValues.isEmpty()) {
        return new AllFalseBitmapColumnIndex(bitmapFactory);
      }
      final boolean matchNull = sortedValues.get(0) == null;
      final Supplier<ImmutableBitmap> unknownsIndex = () -> {
        if (!matchNull && dictionary.get(0) == null) {
          return valueIndexes.get(0);
        }
        return null;
      };
      if (matchValueType.is(ValueType.LONG)) {
        final List<Long> tailSet;
        final List<Long> baseSet = (List<Long>) sortedValues;

        if (sortedValues.size() >= ValueSetIndexes.SIZE_WORTH_CHECKING_MIN) {
          final long minValueInColumn = dictionary.get(0) == null ? dictionary.get(1) : dictionary.get(0);
          final int position = Collections.binarySearch(
              sortedValues,
              minValueInColumn,
              matchValueType.getNullableStrategy()
          );
          tailSet = baseSet.subList(position >= 0 ? position : -(position + 1), baseSet.size());
        } else {
          tailSet = baseSet;
        }
        if (tailSet.size() > ValueSetIndexes.SORTED_SCAN_RATIO_THRESHOLD * dictionary.size()) {
          return ValueSetIndexes.buildBitmapColumnIndexFromSortedIteratorScan(
              bitmapFactory,
              ColumnType.LONG.getNullableStrategy(),
              tailSet,
              tailSet.size(),
              dictionary,
              valueIndexes,
              unknownsIndex
          );
        }
        // fall through to sort value iteration
        return ValueSetIndexes.buildBitmapColumnIndexFromSortedIteratorBinarySearch(
            bitmapFactory,
            tailSet,
            tailSet.size(),
            dictionary,
            valueIndexes,
            unknownsIndex
        );
      } else {
        // values in set are not sorted in double order, transform them on the fly and iterate them all
        return ValueSetIndexes.buildBitmapColumnIndexFromIteratorBinarySearch(
            bitmapFactory,
            Iterables.transform(
                sortedValues,
                DimensionHandlerUtils::convertObjectToLong
            ),
            sortedValues.size(),
            dictionary,
            valueIndexes,
            unknownsIndex
        );
      }
    }
  }

  private class LongStringValueSetIndexes implements StringValueSetIndexes
  {
    final FixedIndexed<Long> dictionary = longDictionarySupplier.get();

    @Override
    public BitmapColumnIndex forValue(@Nullable String value)
    {
      final boolean inputNull = value == null;
      final Long longValue = GuavaUtils.tryParseLong(value);
      return new SimpleBitmapColumnIndex()
      {
        @Override
        public int estimatedComputeCost()
        {
          return 1;
        }

        @Override
        public <T> T computeBitmapResult(BitmapResultFactory<T> bitmapResultFactory, boolean includeUnknown)
        {
          if (longValue == null) {
            if (inputNull) {
              return bitmapResultFactory.wrapDimensionValue(getBitmap(0));
            } else {
              return bitmapResultFactory.wrapDimensionValue(bitmapFactory.makeEmptyImmutableBitmap());
            }
          }
          final int id = dictionary.indexOf(longValue);
          if (includeUnknown) {
            if (id < 0) {
              return bitmapResultFactory.wrapDimensionValue(nullValueBitmap);
            }
            return bitmapResultFactory.unionDimensionValueBitmaps(
                ImmutableList.of(getBitmap(id), nullValueBitmap)
            );
          }
          if (id < 0) {
            return bitmapResultFactory.wrapDimensionValue(bitmapFactory.makeEmptyImmutableBitmap());
          }
          return bitmapResultFactory.wrapDimensionValue(getBitmap(id));
        }
      };
    }

    @Override
    public BitmapColumnIndex forSortedValues(SortedSet<String> values)
    {
      return new SimpleImmutableBitmapDelegatingIterableIndex()
      {
        @Override
        public int estimatedComputeCost()
        {
          return values.size();
        }

        @Override
        public Iterable<ImmutableBitmap> getBitmapIterable()
        {
          LongSet longs = new LongArraySet(values.size());
          boolean needNullCheck = false;
          for (String value : values) {
            if (value == null) {
              needNullCheck = true;
            } else {
              Long theValue = DimensionHandlerUtils.convertObjectToLong(value);
              if (theValue != null) {
                longs.add(theValue.longValue());
              }
            }
          }
          final boolean doNullCheck = needNullCheck;
          return () -> new Iterator<ImmutableBitmap>()
          {
            final FixedIndexed<Long> dictionary = longDictionarySupplier.get();
            final LongIterator iterator = longs.iterator();
            int next = -1;
            boolean nullChecked = false;

            @Override
            public boolean hasNext()
            {
              if (doNullCheck && !nullChecked) {
                return true;
              }
              if (next < 0) {
                findNext();
              }
              return next >= 0;
            }

            @Override
            public ImmutableBitmap next()
            {
              if (doNullCheck && !nullChecked) {
                nullChecked = true;
                return getBitmap(0);
              }
              if (next < 0) {
                findNext();
                if (next < 0) {
                  throw new NoSuchElementException();
                }
              }
              final int swap = next;
              next = -1;
              return getBitmap(swap);
            }

            private void findNext()
            {
              while (next < 0 && iterator.hasNext()) {
                long nextValue = iterator.nextLong();
                next = dictionary.indexOf(nextValue);
              }
            }
          };
        }

        @SuppressFBWarnings("NP_NONNULL_PARAM_VIOLATION")
        @Nullable
        @Override
        protected ImmutableBitmap getUnknownsBitmap()
        {
          if (!values.contains(null)) {
            return nullValueBitmap;
          }
          return null;
        }
      };
    }
  }

  private class LongNumericRangeIndex implements NumericRangeIndexes
  {
    @Nullable
    @Override
    public BitmapColumnIndex forRange(
        @Nullable Number startValue,
        boolean startStrict,
        @Nullable Number endValue,
        boolean endStrict
    )
    {
      final FixedIndexed<Long> dictionary = longDictionarySupplier.get();
      final Long startLong;
      final Long endLong;
      if (startValue == null) {
        startLong = null;
      } else if (startStrict) {
        startLong = (long) Math.floor(startValue.doubleValue());
      } else {
        startLong = (long) Math.ceil(startValue.doubleValue());
      }
      if (endValue == null) {
        endLong = null;
      } else if (endStrict) {
        endLong = (long) Math.ceil(endValue.doubleValue());
      } else {
        endLong = (long) Math.floor(endValue.doubleValue());
      }
      final IntIntPair range = dictionary.getRange(startLong, startStrict, endLong, endStrict);

      final int startIndex = range.leftInt();
      final int endIndex = range.rightInt();
      return new DictionaryRangeScanningBitmapIndex(
          columnConfig.skipValueRangeIndexScale(),
          endIndex - startIndex
      )
      {
        @Override
        public Iterable<ImmutableBitmap> getBitmapIterable()
        {
          return () -> new Iterator<>()
          {
            final IntIterator rangeIterator = IntListUtils.fromTo(startIndex, endIndex).iterator();

            @Override
            public boolean hasNext()
            {
              return rangeIterator.hasNext();
            }

            @Override
            public ImmutableBitmap next()
            {
              return getBitmap(rangeIterator.nextInt());
            }
          };
        }

        @Nullable
        @Override
        protected ImmutableBitmap getUnknownsBitmap()
        {
          return nullValueBitmap;
        }
      };
    }
  }

  private class LongPredicateIndexes implements DruidPredicateIndexes
  {
    @Nullable
    @Override
    public BitmapColumnIndex forPredicate(DruidPredicateFactory matcherFactory)
    {
      FixedIndexed<Long> dictionary = longDictionarySupplier.get();
      return new DictionaryScanningBitmapIndex(dictionary.size())
      {
        @Override
        public Iterable<ImmutableBitmap> getBitmapIterable(boolean includeUnknown)
        {
          return () -> new Iterator<>()
          {
            final Iterator<Long> iterator = dictionary.iterator();
            final DruidLongPredicate longPredicate = matcherFactory.makeLongPredicate();

            int index = -1;
            boolean nextSet = false;

            @Override
            public boolean hasNext()
            {
              if (!nextSet) {
                findNext();
              }
              return nextSet;
            }

            @Override
            public ImmutableBitmap next()
            {
              if (!nextSet) {
                findNext();
                if (!nextSet) {
                  throw new NoSuchElementException();
                }
              }
              nextSet = false;

              return getBitmap(index);
            }

            private void findNext()
            {
              while (!nextSet && iterator.hasNext()) {
                Long nextValue = iterator.next();
                index++;
                if (nextValue == null) {
                  nextSet = longPredicate.applyNull().matches(includeUnknown);
                } else {
                  nextSet = longPredicate.applyLong(nextValue).matches(includeUnknown);
                }
              }
            }
          };
        }
      };
    }
  }

  private class LongDictionaryEncodedValueSetIndex implements DictionaryEncodedStringValueIndex
  {
    private final FixedIndexed<Long> dictionary = longDictionarySupplier.get();

    @Override
    public ImmutableBitmap getBitmap(int idx)
    {
      return ScalarLongColumnAndIndexSupplier.this.getBitmap(idx);
    }

    @Override
    public int getCardinality()
    {
      return dictionary.size();
    }

    @Nullable
    @Override
    public String getValue(int index)
    {
      final Long value = dictionary.get(index);
      return value == null ? null : String.valueOf(value);
    }

    @Override
    public BitmapFactory getBitmapFactory()
    {
      return bitmapFactory;
    }
  }
}
