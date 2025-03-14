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

package org.apache.druid.segment;

import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.base.Strings;
import com.google.common.base.Supplier;
import com.google.common.base.Suppliers;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.google.common.io.Closeables;
import com.google.common.io.Files;
import com.google.common.primitives.Ints;
import com.google.inject.Inject;
import org.apache.druid.collections.bitmap.ConciseBitmapFactory;
import org.apache.druid.collections.bitmap.ImmutableBitmap;
import org.apache.druid.collections.spatial.ImmutableRTree;
import org.apache.druid.common.utils.SerializerUtils;
import org.apache.druid.java.util.common.IAE;
import org.apache.druid.java.util.common.IOE;
import org.apache.druid.java.util.common.ISE;
import org.apache.druid.java.util.common.Intervals;
import org.apache.druid.java.util.common.StringUtils;
import org.apache.druid.java.util.common.io.smoosh.Smoosh;
import org.apache.druid.java.util.common.io.smoosh.SmooshedFileMapper;
import org.apache.druid.java.util.common.logger.Logger;
import org.apache.druid.java.util.emitter.EmittingLogger;
import org.apache.druid.query.aggregation.AggregatorFactory;
import org.apache.druid.segment.column.ColumnBuilder;
import org.apache.druid.segment.column.ColumnCapabilities;
import org.apache.druid.segment.column.ColumnConfig;
import org.apache.druid.segment.column.ColumnDescriptor;
import org.apache.druid.segment.column.ColumnHolder;
import org.apache.druid.segment.column.ColumnType;
import org.apache.druid.segment.column.ValueType;
import org.apache.druid.segment.data.BitmapSerde;
import org.apache.druid.segment.data.BitmapSerdeFactory;
import org.apache.druid.segment.data.CompressedColumnarLongsSupplier;
import org.apache.druid.segment.data.GenericIndexed;
import org.apache.druid.segment.data.ImmutableRTreeObjectStrategy;
import org.apache.druid.segment.data.Indexed;
import org.apache.druid.segment.data.IndexedIterable;
import org.apache.druid.segment.data.ListIndexed;
import org.apache.druid.segment.data.VSizeColumnarMultiInts;
import org.apache.druid.segment.projections.Projections;
import org.apache.druid.segment.serde.ComplexColumnPartSupplier;
import org.apache.druid.segment.serde.FloatNumericColumnSupplier;
import org.apache.druid.segment.serde.LongNumericColumnSupplier;
import org.apache.druid.segment.serde.StringUtf8ColumnIndexSupplier;
import org.apache.druid.segment.serde.StringUtf8DictionaryEncodedColumnSupplier;
import org.joda.time.Interval;

import javax.annotation.Nullable;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public class IndexIO
{
  public static final byte V8_VERSION = 0x8;
  public static final byte V9_VERSION = 0x9;
  public static final int CURRENT_VERSION_ID = V9_VERSION;
  public static final BitmapSerdeFactory LEGACY_FACTORY = new BitmapSerde.LegacyBitmapSerdeFactory();

  public static final ByteOrder BYTE_ORDER = ByteOrder.nativeOrder();
  static final SerializerUtils SERIALIZER_UTILS = new SerializerUtils();

  private final Map<Integer, IndexLoader> indexLoaders;

  private static final EmittingLogger log = new EmittingLogger(IndexIO.class);

  private final ObjectMapper mapper;

  @Inject
  public IndexIO(ObjectMapper mapper, ColumnConfig columnConfig)
  {
    this.mapper = Preconditions.checkNotNull(mapper, "null ObjectMapper");
    Preconditions.checkNotNull(columnConfig, "null ColumnConfig");
    ImmutableMap.Builder<Integer, IndexLoader> indexLoadersBuilder = ImmutableMap.builder();
    LegacyIndexLoader legacyIndexLoader = new LegacyIndexLoader(new DefaultIndexIOHandler());
    for (int i = 0; i <= V8_VERSION; i++) {
      indexLoadersBuilder.put(i, legacyIndexLoader);
    }
    indexLoadersBuilder.put((int) V9_VERSION, new V9IndexLoader(columnConfig));
    indexLoaders = indexLoadersBuilder.build();
  }

  public void validateTwoSegments(File dir1, File dir2) throws IOException
  {
    try (QueryableIndex queryableIndex1 = loadIndex(dir1)) {
      try (QueryableIndex queryableIndex2 = loadIndex(dir2)) {
        validateTwoSegments(
            new QueryableIndexIndexableAdapter(queryableIndex1),
            new QueryableIndexIndexableAdapter(queryableIndex2)
        );
      }
    }
  }

  public void validateTwoSegments(final IndexableAdapter adapter1, final IndexableAdapter adapter2)
  {
    if (adapter1.getNumRows() != adapter2.getNumRows()) {
      throw new SegmentValidationException(
          "Row count mismatch. Expected [%d] found [%d]",
          adapter1.getNumRows(),
          adapter2.getNumRows()
      );
    }
    {
      final Set<String> dimNames1 = Sets.newHashSet(adapter1.getDimensionNames(true));
      final Set<String> dimNames2 = Sets.newHashSet(adapter2.getDimensionNames(true));
      if (!dimNames1.equals(dimNames2)) {
        throw new SegmentValidationException(
            "Dimension names differ. Expected [%s] found [%s]",
            dimNames1,
            dimNames2
        );
      }
      final Set<String> metNames1 = Sets.newHashSet(adapter1.getMetricNames());
      final Set<String> metNames2 = Sets.newHashSet(adapter2.getMetricNames());
      if (!metNames1.equals(metNames2)) {
        throw new SegmentValidationException("Metric names differ. Expected [%s] found [%s]", metNames1, metNames2);
      }
    }
    try (
        final RowIterator it1 = adapter1.getRows();
        final RowIterator it2 = adapter2.getRows()
    ) {
      long row = 0L;
      while (it1.moveToNext()) {
        if (!it2.moveToNext()) {
          throw new SegmentValidationException("Unexpected end of second adapter");
        }
        final RowPointer rp1 = it1.getPointer();
        final RowPointer rp2 = it2.getPointer();
        ++row;
        if (rp1.getRowNum() != rp2.getRowNum()) {
          throw new SegmentValidationException("Row number mismatch: [%d] vs [%d]", rp1.getRowNum(), rp2.getRowNum());
        }
        try {
          validateRowValues(rp1, adapter1, rp2, adapter2);
        }
        catch (SegmentValidationException ex) {
          throw new SegmentValidationException(ex, "Validation failure on row %d: [%s] vs [%s]", row, rp1, rp2);
        }
      }
      if (it2.moveToNext()) {
        throw new SegmentValidationException("Unexpected end of first adapter");
      }
      if (row != adapter1.getNumRows()) {
        throw new SegmentValidationException(
            "Actual Row count mismatch. Expected [%d] found [%d]",
            row,
            adapter1.getNumRows()
        );
      }
    }
  }

  public QueryableIndex loadIndex(File inDir) throws IOException
  {
    return loadIndex(inDir, false, SegmentLazyLoadFailCallback.NOOP);
  }

  public QueryableIndex loadIndex(File inDir, boolean lazy, SegmentLazyLoadFailCallback loadFailed) throws IOException
  {
    final int version = SegmentUtils.getVersionFromDir(inDir);

    final IndexLoader loader = indexLoaders.get(version);

    if (loader != null) {
      return loader.load(inDir, mapper, lazy, loadFailed);
    } else {
      throw new ISE("Unknown index version[%s]", version);
    }
  }

  public static void checkFileSize(File indexFile) throws IOException
  {
    final long fileSize = indexFile.length();
    if (fileSize > Integer.MAX_VALUE) {
      throw new IOE("File[%s] too large[%d]", indexFile, fileSize);
    }
  }

  interface IndexIOHandler
  {
    MMappedIndex mapDir(File inDir) throws IOException;
  }

  private static void validateRowValues(
      RowPointer rp1,
      IndexableAdapter adapter1,
      RowPointer rp2,
      IndexableAdapter adapter2
  )
  {
    if (rp1.getTimestamp() != rp2.getTimestamp()) {
      throw new SegmentValidationException(
          "Timestamp mismatch. Expected %d found %d",
          rp1.getTimestamp(),
          rp2.getTimestamp()
      );
    }
    final List<Object> dims1 = rp1.getDimensionValuesForDebug();
    final List<Object> dims2 = rp2.getDimensionValuesForDebug();
    if (dims1.size() != dims2.size()) {
      throw new SegmentValidationException("Dim lengths not equal %s vs %s", dims1, dims2);
    }
    final List<String> dim1Names = adapter1.getDimensionNames(false);
    final List<String> dim2Names = adapter2.getDimensionNames(false);
    int dimCount = dims1.size();
    for (int i = 0; i < dimCount; ++i) {
      final String dim1Name = dim1Names.get(i);
      final String dim2Name = dim2Names.get(i);

      ColumnCapabilities capabilities1 = adapter1.getCapabilities(dim1Name);
      ColumnCapabilities capabilities2 = adapter2.getCapabilities(dim2Name);
      ColumnType dim1Type = capabilities1.toColumnType();
      ColumnType dim2Type = capabilities2.toColumnType();
      if (!Objects.equals(dim1Type, dim2Type)) {
        throw new SegmentValidationException(
            "Dim [%s] types not equal. Expected %d found %d",
            dim1Name,
            dim1Type,
            dim2Type
        );
      }

      Object vals1 = dims1.get(i);
      Object vals2 = dims2.get(i);
      if (isNullRow(vals1) ^ isNullRow(vals2)) {
        throw notEqualValidationException(dim1Name, vals1, vals2);
      }
      boolean vals1IsList = vals1 instanceof List;
      boolean vals2IsList = vals2 instanceof List;
      if (vals1IsList ^ vals2IsList) {
        if (vals1IsList) {
          if (((List) vals1).size() != 1 || !Objects.equals(((List) vals1).get(0), vals2)) {
            throw notEqualValidationException(dim1Name, vals1, vals2);
          }
        } else {
          if (((List) vals2).size() != 1 || !Objects.equals(((List) vals2).get(0), vals1)) {
            throw notEqualValidationException(dim1Name, vals1, vals2);
          }
        }
      } else if (vals1 instanceof Object[]) {
        if (!Arrays.deepEquals((Object[]) vals1, (Object[]) vals2)) {
          throw notEqualValidationException(dim1Name, vals1, vals2);
        }
      } else {
        if (!Objects.equals(vals1, vals2)) {
          throw notEqualValidationException(dim1Name, vals1, vals2);
        }
      }
    }
  }

  private static boolean isNullRow(@Nullable Object row)
  {
    if (row == null) {
      return true;
    }
    if (!(row instanceof List)) {
      return false;
    }
    List<?> rowAsList = (List<?>) row;
    //noinspection ForLoopReplaceableByForEach -- in order to not create a garbage iterator object
    for (int i = 0, rowSize = rowAsList.size(); i < rowSize; i++) {
      Object v = rowAsList.get(i);
      //noinspection VariableNotUsedInsideIf
      if (v != null) {
        return false;
      }
    }
    return true;
  }

  private static SegmentValidationException notEqualValidationException(String dimName, Object v1, Object v2)
  {
    return new SegmentValidationException("Dim [%s] values not equal. Expected %s found %s", dimName, v1, v2);
  }

  public static class DefaultIndexIOHandler implements IndexIOHandler
  {
    private static final Logger log = new Logger(DefaultIndexIOHandler.class);

    @Override
    public MMappedIndex mapDir(File inDir) throws IOException
    {
      log.debug("Mapping v8 index[%s]", inDir);
      long startTime = System.currentTimeMillis();

      InputStream indexIn = null;
      try {
        indexIn = new FileInputStream(new File(inDir, "index.drd"));
        byte theVersion = (byte) indexIn.read();
        if (theVersion != V8_VERSION) {
          throw new IAE("Unknown version[%d]", theVersion);
        }
      }
      finally {
        Closeables.close(indexIn, false);
      }

      SmooshedFileMapper smooshedFiles = Smoosh.map(inDir);
      ByteBuffer indexBuffer = smooshedFiles.mapFile("index.drd");

      indexBuffer.get(); // Skip the version byte
      final GenericIndexed<String> availableDimensions = GenericIndexed.read(
          indexBuffer,
          GenericIndexed.STRING_STRATEGY,
          smooshedFiles
      );
      final GenericIndexed<String> availableMetrics = GenericIndexed.read(
          indexBuffer,
          GenericIndexed.STRING_STRATEGY,
          smooshedFiles
      );
      final Interval dataInterval = Intervals.of(SERIALIZER_UTILS.readString(indexBuffer));
      final BitmapSerdeFactory bitmapSerdeFactory = new BitmapSerde.LegacyBitmapSerdeFactory();

      CompressedColumnarLongsSupplier timestamps = CompressedColumnarLongsSupplier.fromByteBuffer(
          smooshedFiles.mapFile(makeTimeFile(inDir, BYTE_ORDER).getName()),
          BYTE_ORDER,
          smooshedFiles
      );

      Map<String, MetricHolder> metrics = Maps.newLinkedHashMap();
      for (String metric : availableMetrics) {
        final String metricFilename = makeMetricFile(inDir, metric, BYTE_ORDER).getName();
        final MetricHolder holder = MetricHolder.fromByteBuffer(smooshedFiles.mapFile(metricFilename));

        if (!metric.equals(holder.getName())) {
          throw new ISE("Metric[%s] loaded up metric[%s] from disk.  File names do matter.", metric, holder.getName());
        }
        metrics.put(metric, holder);
      }

      Map<String, GenericIndexed<ByteBuffer>> dimValueUtf8Lookups = new HashMap<>();
      Map<String, VSizeColumnarMultiInts> dimColumns = new HashMap<>();
      Map<String, GenericIndexed<ImmutableBitmap>> bitmaps = new HashMap<>();

      for (String dimension : IndexedIterable.create(availableDimensions)) {
        ByteBuffer dimBuffer = smooshedFiles.mapFile(makeDimFile(inDir, dimension).getName());
        String fileDimensionName = SERIALIZER_UTILS.readString(dimBuffer);
        Preconditions.checkState(
            dimension.equals(fileDimensionName),
            "Dimension file[%s] has dimension[%s] in it!?",
            makeDimFile(inDir, dimension),
            fileDimensionName
        );

        dimValueUtf8Lookups.put(
            dimension,
            GenericIndexed.read(dimBuffer, GenericIndexed.UTF8_STRATEGY, smooshedFiles)
        );
        dimColumns.put(dimension, VSizeColumnarMultiInts.readFromByteBuffer(dimBuffer));
      }

      ByteBuffer invertedBuffer = smooshedFiles.mapFile("inverted.drd");
      for (int i = 0; i < availableDimensions.size(); ++i) {
        bitmaps.put(
            SERIALIZER_UTILS.readString(invertedBuffer),
            GenericIndexed.read(invertedBuffer, bitmapSerdeFactory.getObjectStrategy(), smooshedFiles)
        );
      }

      Map<String, ImmutableRTree> spatialIndexed = new HashMap<>();
      ByteBuffer spatialBuffer = smooshedFiles.mapFile("spatial.drd");
      while (spatialBuffer != null && spatialBuffer.hasRemaining()) {
        spatialIndexed.put(
            SERIALIZER_UTILS.readString(spatialBuffer),
            new ImmutableRTreeObjectStrategy(bitmapSerdeFactory.getBitmapFactory()).fromByteBufferWithSize(
                spatialBuffer
            )
        );
      }

      final MMappedIndex retVal = new MMappedIndex(
          availableDimensions,
          availableMetrics,
          dataInterval,
          timestamps,
          metrics,
          dimValueUtf8Lookups,
          dimColumns,
          bitmaps,
          spatialIndexed,
          smooshedFiles
      );

      log.debug("Mapped v8 index[%s] in %,d millis", inDir, System.currentTimeMillis() - startTime);

      return retVal;
    }
  }

  interface IndexLoader
  {
    QueryableIndex load(File inDir, ObjectMapper mapper, boolean lazy, SegmentLazyLoadFailCallback loadFailed) throws IOException;
  }

  static class LegacyIndexLoader implements IndexLoader
  {
    private final IndexIOHandler legacyHandler;

    LegacyIndexLoader(IndexIOHandler legacyHandler)
    {
      this.legacyHandler = legacyHandler;
    }

    @Override
    public QueryableIndex load(File inDir, ObjectMapper mapper, boolean lazy, SegmentLazyLoadFailCallback loadFailed) throws IOException
    {
      MMappedIndex index = legacyHandler.mapDir(inDir);

      Map<String, Supplier<ColumnHolder>> columns = new LinkedHashMap<>();

      for (String dimension : index.getAvailableDimensions()) {
        ColumnBuilder builder = new ColumnBuilder()
            .setType(ValueType.STRING)
            .setHasMultipleValues(true)
            .setDictionaryEncodedColumnSupplier(
                new StringUtf8DictionaryEncodedColumnSupplier<>(
                    index.getDimValueUtf8Lookup(dimension)::singleThreaded,
                    null,
                    Suppliers.ofInstance(index.getDimColumn(dimension)),
                    LEGACY_FACTORY.getBitmapFactory()
                )
            );
        GenericIndexed<ImmutableBitmap> bitmaps = index.getBitmapIndexes().get(dimension);
        ImmutableRTree spatialIndex = index.getSpatialIndexes().get(dimension);
        builder.setIndexSupplier(
            new StringUtf8ColumnIndexSupplier<>(
                new ConciseBitmapFactory(),
                index.getDimValueUtf8Lookup(dimension)::singleThreaded,
                bitmaps,
                spatialIndex
            ),
            bitmaps != null,
            spatialIndex != null
        );
        columns.put(dimension, getColumnHolderSupplier(builder, lazy));
      }

      for (String metric : index.getAvailableMetrics()) {
        final MetricHolder metricHolder = index.getMetricHolder(metric);
        if (metricHolder.getType() == MetricHolder.MetricType.FLOAT) {
          ColumnBuilder builder = new ColumnBuilder()
              .setType(ValueType.FLOAT)
              .setNumericColumnSupplier(
                  new FloatNumericColumnSupplier(
                      metricHolder.floatType,
                      LEGACY_FACTORY.getBitmapFactory().makeEmptyImmutableBitmap()
                  )
              );
          columns.put(metric, getColumnHolderSupplier(builder, lazy));
        } else if (metricHolder.getType() == MetricHolder.MetricType.COMPLEX) {
          ColumnBuilder builder = new ColumnBuilder()
              .setType(ValueType.COMPLEX)
              .setComplexColumnSupplier(
                  new ComplexColumnPartSupplier(metricHolder.getTypeName(), metricHolder.complexType)
              );
          columns.put(metric, getColumnHolderSupplier(builder, lazy));
        }
      }

      ColumnBuilder builder = new ColumnBuilder()
          .setType(ValueType.LONG)
          .setNumericColumnSupplier(
              new LongNumericColumnSupplier(
                  index.timestamps,
                  LEGACY_FACTORY.getBitmapFactory().makeEmptyImmutableBitmap()
              )
          );
      columns.put(ColumnHolder.TIME_COLUMN_NAME, getColumnHolderSupplier(builder, lazy));

      return new SimpleQueryableIndex(
          index.getDataInterval(),
          index.getAvailableDimensions(),
          new ConciseBitmapFactory(),
          columns,
          index.getFileMapper()
      )
      {
        @Override
        public Metadata getMetadata()
        {
          return null;
        }
      };
    }

    private Supplier<ColumnHolder> getColumnHolderSupplier(ColumnBuilder builder, boolean lazy)
    {
      if (lazy) {
        return Suppliers.memoize(builder::build);
      } else {
        ColumnHolder columnHolder = builder.build();
        return () -> columnHolder;
      }
    }
  }

  static class V9IndexLoader implements IndexLoader
  {
    private final ColumnConfig columnConfig;

    V9IndexLoader(ColumnConfig columnConfig)
    {
      this.columnConfig = columnConfig;
    }

    @Override
    public QueryableIndex load(File inDir, ObjectMapper mapper, boolean lazy, SegmentLazyLoadFailCallback loadFailed)
        throws IOException
    {
      log.debug("Mapping v9 index[%s]", inDir);
      long startTime = System.currentTimeMillis();

      final int theVersion = Ints.fromByteArray(Files.toByteArray(new File(inDir, "version.bin")));
      if (theVersion != V9_VERSION) {
        throw new IAE("Expected version[9], got[%d]", theVersion);
      }

      SmooshedFileMapper smooshedFiles = Smoosh.map(inDir);

      ByteBuffer indexBuffer = smooshedFiles.mapFile("index.drd");
      /**
       * Index.drd should consist of the segment version, the columns and dimensions of the segment as generic
       * indexes, the interval start and end millis as longs (in 16 bytes), and a bitmap index type.
       */
      final GenericIndexed<String> nonNullCols = GenericIndexed.read(
          indexBuffer,
          GenericIndexed.STRING_STRATEGY,
          smooshedFiles
      );
      final GenericIndexed<String> nonNullDims = GenericIndexed.read(
          indexBuffer,
          GenericIndexed.STRING_STRATEGY,
          smooshedFiles
      );
      final Interval dataInterval = Intervals.utc(indexBuffer.getLong(), indexBuffer.getLong());
      final BitmapSerdeFactory segmentBitmapSerdeFactory;

      // These can be null if the segment is created in an older version than 0.23.0
      // as they don't store null-only columns in the segment.
      @Nullable final GenericIndexed<String> allCols;
      @Nullable final GenericIndexed<String> allDims;

      /**
       * This is a workaround for the fact that in v8 segments, we have no information about the type of bitmap
       * index to use. Since we cannot very cleanly build v9 segments directly, we are using a workaround where
       * this information is appended to the end of index.drd.
       */
      if (indexBuffer.hasRemaining()) {
        segmentBitmapSerdeFactory = mapper.readValue(
            SERIALIZER_UTILS.readString(indexBuffer),
            BitmapSerdeFactory.class
        );

        if (indexBuffer.hasRemaining()) {
          allCols = GenericIndexed.read(
              indexBuffer,
              GenericIndexed.STRING_STRATEGY,
              smooshedFiles
          );
          allDims = GenericIndexed.read(
              indexBuffer,
              GenericIndexed.STRING_STRATEGY,
              smooshedFiles
          );
        } else {
          allCols = null;
          allDims = null;
        }
      } else {
        segmentBitmapSerdeFactory = new BitmapSerde.LegacyBitmapSerdeFactory();
        allCols = null;
        allDims = null;
      }

      Map<String, Supplier<ColumnHolder>> columns = new LinkedHashMap<>();

      // Register the time column
      ByteBuffer timeBuffer = smooshedFiles.mapFile("__time");
      registerColumnHolder(
          lazy,
          columns,
          ColumnHolder.TIME_COLUMN_NAME,
          mapper,
          timeBuffer,
          smooshedFiles,
          null,
          loadFailed
      );

      final Indexed<String> finalCols, finalDims;

      if (allCols != null) {
        // To restore original column order, we merge allCols/allDims and nonNullCols/nonNullDims, respectively.
        finalCols = new ListIndexed<>(restoreColumns(nonNullCols, allCols));
        finalDims = new ListIndexed<>(restoreColumns(nonNullDims, allDims));
      } else {
        finalCols = nonNullCols;
        finalDims = nonNullDims;
      }
      registerColumnHolders(
          inDir,
          finalCols,
          lazy,
          columns,
          mapper,
          smooshedFiles,
          loadFailed
      );
      final Map<String, Map<String, Supplier<ColumnHolder>>> projectionsColumns = new LinkedHashMap<>();
      final Metadata metadata = getMetdata(smooshedFiles, mapper, inDir);
      if (metadata != null && metadata.getProjections() != null) {
        for (AggregateProjectionMetadata projectionSpec : metadata.getProjections()) {
          final Map<String, Supplier<ColumnHolder>> projectionColumns = readProjectionColumns(
              mapper,
              loadFailed,
              projectionSpec,
              smooshedFiles,
              columns,
              dataInterval
          );

          projectionsColumns.put(projectionSpec.getSchema().getName(), projectionColumns);
        }
      }

      final QueryableIndex index = new SimpleQueryableIndex(
          dataInterval,
          finalDims,
          segmentBitmapSerdeFactory.getBitmapFactory(),
          columns,
          smooshedFiles,
          metadata,
          projectionsColumns
      )
      {
        @Override
        public Metadata getMetadata()
        {
          return getMetdata(smooshedFiles, mapper, inDir);
        }
      };

      log.debug("Mapped v9 index[%s] in %,d millis", inDir, System.currentTimeMillis() - startTime);

      return index;
    }

    private Map<String, Supplier<ColumnHolder>> readProjectionColumns(
        ObjectMapper mapper,
        SegmentLazyLoadFailCallback loadFailed,
        AggregateProjectionMetadata projectionSpec,
        SmooshedFileMapper smooshedFiles,
        Map<String, Supplier<ColumnHolder>> columns,
        Interval dataInterval
    ) throws IOException
    {
      final Map<String, Supplier<ColumnHolder>> projectionColumns = new LinkedHashMap<>();
      for (String groupingColumn : projectionSpec.getSchema().getGroupingColumns()) {
        final String smooshName = Projections.getProjectionSmooshV9FileName(projectionSpec, groupingColumn);
        final ByteBuffer colBuffer = smooshedFiles.mapFile(smooshName);

        final ColumnHolder parentColumn;
        if (columns.containsKey(groupingColumn)) {
          parentColumn = columns.get(groupingColumn).get();
        } else {
          parentColumn = null;
        }
        registerColumnHolder(
            true,
            projectionColumns,
            groupingColumn,
            mapper,
            colBuffer,
            smooshedFiles,
            parentColumn,
            loadFailed
        );

        if (groupingColumn.equals(projectionSpec.getSchema().getTimeColumnName())) {
          projectionColumns.put(ColumnHolder.TIME_COLUMN_NAME, projectionColumns.get(groupingColumn));
          projectionColumns.remove(groupingColumn);
        }
      }
      for (AggregatorFactory aggregator : projectionSpec.getSchema().getAggregators()) {
        final String smooshName = Projections.getProjectionSmooshV9FileName(projectionSpec, aggregator.getName());
        final ByteBuffer aggBuffer = smooshedFiles.mapFile(smooshName);
        registerColumnHolder(
            true,
            projectionColumns,
            aggregator.getName(),
            mapper,
            aggBuffer,
            smooshedFiles,
            null,
            loadFailed
        );
      }
      if (projectionSpec.getSchema().getTimeColumnName() == null) {
        projectionColumns.put(
            ColumnHolder.TIME_COLUMN_NAME,
            Projections.makeConstantTimeSupplier(projectionSpec.getNumRows(), dataInterval.getStartMillis())
        );
      }
      return projectionColumns;
    }

    @Nullable
    private Metadata getMetdata(SmooshedFileMapper smooshedFiles, ObjectMapper mapper, File inDir)
    {
      try {
        ByteBuffer metadataBB = smooshedFiles.mapFile("metadata.drd");
        if (metadataBB != null) {
          return mapper.readValue(
              SERIALIZER_UTILS.readBytes(metadataBB, metadataBB.remaining()),
              Metadata.class
          );
        }
      }
      catch (JsonParseException | JsonMappingException ex) {
        // Any jackson deserialization errors are ignored e.g. if metadata contains some aggregator which
        // is no longer supported then it is OK to not use the metadata instead of failing segment loading
        log.warn(ex, "Failed to load metadata for segment [%s]", inDir);
      }
      catch (IOException ex) {
        log.warn(ex, "Failed to read metadata for segment [%s]", inDir);
      }
      return null;
    }

    /**
     * Return a list of columns that contains given inputs merged. The returned column names are in
     * the original order that is used when this segment is created.
     *
     * The original column order is encoded in two input GenericIndexeds. nonNullCols have only non-null columns,
     * while allCols have null-only columns and nulls.
     * In allCols, null is stored instead of actual column name
     * at the positions corresponding to non-null columns in the original column order. At other positions,
     * the name of null columns are stored. See IndexMergerV9.makeIndexBinary() for more details of how column order
     * is encoded.
     */
    private List<String> restoreColumns(GenericIndexed<String> nonNullCols, GenericIndexed<String> allCols)
    {
      final List<String> mergedCols = new ArrayList<>(allCols.size());
      Iterator<String> allColsIterator = allCols.iterator();
      Iterator<String> nonNullColsIterator = nonNullCols.iterator();
      while (allColsIterator.hasNext()) {
        final String next = allColsIterator.next();
        if (next == null) {
          Preconditions.checkState(
              nonNullColsIterator.hasNext(),
              "There is no more column name to iterate in nonNullColsIterator "
              + "while allColsIterator expects one. This is likely a potential bug in creating this segment. "
              + "Try reingesting your data with storeEmptyColumns setting to false in task context."
          );
          mergedCols.add(SmooshedFileMapper.STRING_INTERNER.intern(nonNullColsIterator.next()));
        } else {
          mergedCols.add(SmooshedFileMapper.STRING_INTERNER.intern(next));
        }
      }

      return mergedCols;
    }

    private void registerColumnHolders(
        File inDir,
        Indexed<String> cols,
        boolean lazy,
        Map<String, Supplier<ColumnHolder>> columns,
        ObjectMapper mapper,
        SmooshedFileMapper smooshedFiles,
        SegmentLazyLoadFailCallback loadFailed
    ) throws IOException
    {
      for (String columnName : cols) {
        if (Strings.isNullOrEmpty(columnName)) {
          log.warn("Null or Empty Dimension found in the file : " + inDir);
          continue;
        }

        final ByteBuffer colBuffer = smooshedFiles.mapFile(columnName);
        registerColumnHolder(
            lazy,
            columns,
            columnName,
            mapper,
            colBuffer,
            smooshedFiles,
            null,
            loadFailed
        );
      }
    }

    private void registerColumnHolder(
        boolean lazy,
        Map<String, Supplier<ColumnHolder>> columns,
        String columnName,
        ObjectMapper mapper,
        ByteBuffer colBuffer,
        SmooshedFileMapper smooshedFiles,
        @Nullable ColumnHolder parentColumn,
        SegmentLazyLoadFailCallback loadFailed
    ) throws IOException
    {

      // we use the interner here too even though it might have already been added by restoreColumns(..) because that
      // only happens if there are some null columns
      final String internedColumnName = SmooshedFileMapper.STRING_INTERNER.intern(columnName);
      if (lazy) {
        columns.put(internedColumnName, Suppliers.memoize(
            () -> {
              try {
                return deserializeColumn(
                    internedColumnName,
                    mapper,
                    colBuffer,
                    smooshedFiles,
                    parentColumn
                );
              }
              catch (IOException | RuntimeException e) {
                log.warn(e, "Throw exceptions when deserialize column [%s].", columnName);
                loadFailed.execute();
                throw Throwables.propagate(e);
              }
            }
        ));
      } else {
        final ColumnHolder columnHolder = deserializeColumn(
            internedColumnName,
            mapper,
            colBuffer,
            smooshedFiles,
            parentColumn
        );
        columns.put(internedColumnName, () -> columnHolder);
      }
    }

    /**
     * Deserialize a column from the given ByteBuffer.
     * Visible for failure testing. See {@link V9IndexLoaderTest#testLoadSegmentDamagedFileWithLazy()}.
     */
    @VisibleForTesting
    ColumnHolder deserializeColumn(
        String columnName, // columnName is not used in this method, but used in tests.
        ObjectMapper mapper,
        ByteBuffer byteBuffer,
        SmooshedFileMapper smooshedFiles,
        @Nullable ColumnHolder parentColumn
    ) throws IOException
    {
      ColumnDescriptor serde = mapper.readValue(SERIALIZER_UTILS.readString(byteBuffer), ColumnDescriptor.class);
      return serde.read(byteBuffer, columnConfig, smooshedFiles, parentColumn);
    }
  }

  public static File makeDimFile(File dir, String dimension)
  {
    return new File(dir, StringUtils.format("dim_%s.drd", dimension));
  }

  public static File makeTimeFile(File dir, ByteOrder order)
  {
    return new File(dir, StringUtils.format("time_%s.drd", order));
  }

  public static File makeMetricFile(File dir, String metricName, ByteOrder order)
  {
    return new File(dir, StringUtils.format("met_%s_%s.drd", metricName, order));
  }
}
