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

package org.apache.druid.server.coordinator;

import com.google.common.collect.ImmutableList;
import org.apache.druid.client.DataSourcesSnapshot;
import org.apache.druid.jackson.DefaultObjectMapper;
import org.apache.druid.java.util.common.DateTimes;
import org.apache.druid.server.compaction.CompactionCandidateSearchPolicy;
import org.apache.druid.server.compaction.CompactionSegmentIterator;
import org.apache.druid.server.compaction.CompactionStatusTracker;
import org.apache.druid.server.compaction.NewestSegmentFirstPolicy;
import org.apache.druid.server.compaction.PriorityBasedCompactionSegmentIterator;
import org.apache.druid.timeline.DataSegment;
import org.apache.druid.timeline.SegmentTimeline;
import org.apache.druid.timeline.partition.NumberedShardSpec;
import org.apache.druid.timeline.partition.ShardSpec;
import org.joda.time.DateTime;
import org.joda.time.Interval;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.infra.Blackhole;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Fork(value = 1, jvmArgsAppend = "-XX:+UseG1GC")
public class NewestSegmentFirstPolicyBenchmark
{
  private static final String DATA_SOURCE_PREFIX = "dataSource_";

  private final CompactionCandidateSearchPolicy policy = new NewestSegmentFirstPolicy(null);

  @Param("100")
  private int numDataSources;

  @Param("10000")
  private int numDayIntervalsPerDataSource;

  @Param("10")
  private int numPartitionsPerDayInterval;

  @Param("800000000")
  private long inputSegmentSizeBytes;

  @Param("1000000")
  private long segmentSizeBytes;

  @Param("10")
  private int numCompactionTaskSlots;

  private Map<String, DataSourceCompactionConfig> compactionConfigs;
  private Map<String, SegmentTimeline> dataSources;

  @Setup(Level.Trial)
  public void setup()
  {
    compactionConfigs = new HashMap<>();
    for (int i = 0; i < numDataSources; i++) {
      final String dataSource = DATA_SOURCE_PREFIX + i;
      compactionConfigs.put(
          dataSource,
          InlineSchemaDataSourceCompactionConfig
              .builder()
              .forDataSource(dataSource)
              .withTaskPriority(0)
              .withInputSegmentSizeBytes(inputSegmentSizeBytes)
              .build()
      );
    }

    List<DataSegment> segments = new ArrayList<>();
    for (int i = 0; i < numDataSources; i++) {
      final String dataSource = DATA_SOURCE_PREFIX + i;

      final int startYear = ThreadLocalRandom.current().nextInt(2000, 2040);
      DateTime date = DateTimes.of(startYear, 1, 1, 0, 0);

      for (int j = 0; j < numDayIntervalsPerDataSource; j++, date = date.plusDays(1)) {
        for (int k = 0; k < numPartitionsPerDayInterval; k++) {
          final ShardSpec shardSpec = new NumberedShardSpec(numPartitionsPerDayInterval, k);
          final DataSegment segment = new DataSegment(
              dataSource,
              new Interval(date, date.plusDays(1)),
              "version",
              null,
              ImmutableList.of(),
              ImmutableList.of(),
              shardSpec,
              0,
              segmentSizeBytes
          );
          segments.add(segment);
        }
      }
    }
    dataSources = DataSourcesSnapshot.fromUsedSegments(segments).getUsedSegmentsTimelinesPerDataSource();
  }

  @Benchmark
  public void measureNewestSegmentFirstPolicy(Blackhole blackhole)
  {
    final CompactionSegmentIterator iterator = new PriorityBasedCompactionSegmentIterator(
        policy,
        compactionConfigs,
        dataSources,
        Collections.emptyMap(),
        new CompactionStatusTracker(new DefaultObjectMapper())
    );
    for (int i = 0; i < numCompactionTaskSlots && iterator.hasNext(); i++) {
      blackhole.consume(iterator.next());
    }
  }
}
