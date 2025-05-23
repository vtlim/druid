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

package org.apache.druid.indexer;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import org.apache.druid.data.input.impl.DelimitedParseSpec;
import org.apache.druid.data.input.impl.DimensionsSpec;
import org.apache.druid.data.input.impl.StringInputRowParser;
import org.apache.druid.data.input.impl.TimestampSpec;
import org.apache.druid.indexer.granularity.UniformGranularitySpec;
import org.apache.druid.indexer.partitions.HashedPartitionsSpec;
import org.apache.druid.java.util.common.DateTimes;
import org.apache.druid.java.util.common.FileUtils;
import org.apache.druid.java.util.common.Intervals;
import org.apache.druid.java.util.common.granularity.Granularities;
import org.apache.druid.java.util.common.granularity.Granularity;
import org.apache.druid.java.util.common.granularity.PeriodGranularity;
import org.apache.druid.query.aggregation.DoubleSumAggregatorFactory;
import org.apache.druid.segment.indexing.DataSchema;
import org.apache.druid.timeline.partition.HashBasedNumberedShardSpec;
import org.apache.druid.timeline.partition.HashPartitionFunction;
import org.joda.time.Interval;
import org.joda.time.Period;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import javax.annotation.Nullable;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;

@RunWith(Parameterized.class)
public class DetermineHashedPartitionsJobTest
{
  private HadoopDruidIndexerConfig indexerConfig;
  private int expectedNumTimeBuckets;
  private ArrayList<Integer> expectedNumOfShards;
  private int errorMargin;

  @Parameterized.Parameters(name = "File={0}, TargetPartitionSize={1}, Interval={2}, ErrorMargin={3}, NumTimeBuckets={4}, NumShards={5}, SegmentGranularity={6}")
  public static Collection<?> data()
  {
    ArrayList<Integer> first = makeListOf(1, 13);
    ArrayList<Integer> second = makeListOf(6, 1);
    ArrayList<Integer> third = makeListOf(6, 13);
    third.set(2, 12);
    third.set(5, 11);

    return Arrays.asList(
        new Object[][]{
            {
                DetermineHashedPartitionsJobTest.class.getResource("/druid.test.data.with.duplicate.rows.tsv").getPath(),
                1,
                "2011-04-10T00:00:00.000Z/2011-04-11T00:00:00.000Z",
                0,
                1,
                first,
                Granularities.DAY,
                null
            },
            {
                DetermineHashedPartitionsJobTest.class.getResource("/druid.test.data.with.duplicate.rows.tsv").getPath(),
                100,
                "2011-04-10T00:00:00.000Z/2011-04-16T00:00:00.000Z",
                0,
                6,
                second,
                Granularities.DAY,
                null
            },
            {
                DetermineHashedPartitionsJobTest.class.getResource("/druid.test.data.with.duplicate.rows.tsv").getPath(),
                1,
                "2011-04-10T00:00:00.000Z/2011-04-16T00:00:00.000Z",
                0,
                6,
                third,
                Granularities.DAY,
                null
            },
            {
                DetermineHashedPartitionsJobTest.class.getResource("/druid.test.data.with.duplicate.rows.tsv").getPath(),
                1,
                null,
                0,
                6,
                third,
                Granularities.DAY,
                null
            },
            {
                DetermineHashedPartitionsJobTest.class.getResource("/druid.test.data.with.duplicate.rows.tsv").getPath(),
                1,
                null,
                0,
                6,
                third,
                Granularities.DAY,
                HashPartitionFunction.MURMUR3_32_ABS
            },
            {
                DetermineHashedPartitionsJobTest.class.getResource("/druid.test.data.with.rows.in.timezone.tsv").getPath(),
                1,
                null,
                0,
                1,
                first,
                new PeriodGranularity(new Period("P1D"), null, DateTimes.inferTzFromString("America/Los_Angeles")),
                null
            }
        }
    );
  }

  public DetermineHashedPartitionsJobTest(
      String dataFilePath,
      int targetPartitionSize,
      String interval,
      int errorMargin,
      int expectedNumTimeBuckets,
      ArrayList<Integer> expectedNumOfShards,
      Granularity segmentGranularity,
      @Nullable HashPartitionFunction partitionFunction
  )
  {
    this.expectedNumOfShards = expectedNumOfShards;
    this.expectedNumTimeBuckets = expectedNumTimeBuckets;
    this.errorMargin = errorMargin;
    File tmpDir = FileUtils.createTempDir();

    ImmutableList<Interval> intervals = null;
    if (interval != null) {
      intervals = ImmutableList.of(Intervals.of(interval));
    }

    HadoopIngestionSpec ingestionSpec = new HadoopIngestionSpec(
        DataSchema.builder()
                  .withDataSource("test_schema")
                  .withParserMap(HadoopDruidIndexerConfig.JSON_MAPPER.convertValue(
                      new StringInputRowParser(
                          new DelimitedParseSpec(
                              new TimestampSpec("ts", null, null),
                              new DimensionsSpec(
                                  DimensionsSpec.getDefaultSchemas(ImmutableList.of(
                                      "market",
                                      "quality",
                                      "placement",
                                      "placementish"
                                  ))
                              ),
                              "\t",
                              null,
                              Arrays.asList(
                                  "ts",
                                  "market",
                                  "quality",
                                  "placement",
                                  "placementish",
                                  "index"
                              ),
                              false,
                              0
                          ),
                          null
                      ),
                      Map.class
                  ))
                  .withAggregators(new DoubleSumAggregatorFactory("index", "index"))
                  .withGranularity(new UniformGranularitySpec(
                      segmentGranularity,
                      Granularities.NONE,
                      intervals
                  ))
                  .withObjectMapper(HadoopDruidIndexerConfig.JSON_MAPPER)
                  .build(),
        new HadoopIOConfig(
            ImmutableMap.of(
                "paths",
                dataFilePath,
                "type",
                "static"
            ), null, tmpDir.getAbsolutePath()
        ),
        new HadoopTuningConfig(
            tmpDir.getAbsolutePath(),
            null,
            new HashedPartitionsSpec(targetPartitionSize, null, null, partitionFunction),
            null,
            null,
            null,
            null,
            null,
            null,
            false,
            false,
            false,
            false,
            null,
            false,
            false,
            null,
            null,
            false,
            false,
            null,
            null,
            null,
            null,
            null,
            1
        )
    );
    this.indexerConfig = new HadoopDruidIndexerConfig(ingestionSpec);
  }

  @Test
  public void testDetermineHashedPartitions()
  {
    DetermineHashedPartitionsJob determineHashedPartitionsJob = new DetermineHashedPartitionsJob(indexerConfig);
    determineHashedPartitionsJob.run();
    HashPartitionFunction expectedFunction = ((HashedPartitionsSpec) indexerConfig.getPartitionsSpec())
        .getPartitionFunction();
    Map<Long, List<HadoopyShardSpec>> shardSpecs = indexerConfig.getSchema().getTuningConfig().getShardSpecs();
    Assert.assertEquals(
        expectedNumTimeBuckets,
        shardSpecs.size()
    );
    int i = 0;
    for (Map.Entry<Long, List<HadoopyShardSpec>> entry : shardSpecs.entrySet()) {
      Assert.assertEquals(
          expectedNumOfShards.get(i++),
          entry.getValue().size(),
          errorMargin
      );
      for (HadoopyShardSpec eachShardSpec : entry.getValue()) {
        final HashBasedNumberedShardSpec hashShardSpec = (HashBasedNumberedShardSpec) eachShardSpec.getActualSpec();
        Assert.assertEquals(expectedFunction, hashShardSpec.getPartitionFunction());
      }
    }
  }

  private static ArrayList<Integer> makeListOf(int capacity, int value)
  {
    ArrayList<Integer> retVal = new ArrayList<>();
    for (int i = 0; i < capacity; ++i) {
      retVal.add(value);
    }
    return retVal;
  }
}
