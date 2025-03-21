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

package org.apache.druid.query.aggregation.post;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import org.apache.druid.java.util.common.granularity.Granularities;
import org.apache.druid.query.Druids;
import org.apache.druid.query.aggregation.CountAggregator;
import org.apache.druid.query.aggregation.CountAggregatorFactory;
import org.apache.druid.query.aggregation.PostAggregator;
import org.apache.druid.query.timeseries.TimeseriesQuery;
import org.apache.druid.query.timeseries.TimeseriesQueryQueryToolChest;
import org.apache.druid.segment.column.ColumnType;
import org.apache.druid.segment.column.RowSignature;
import org.junit.Assert;
import org.junit.Test;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 */
public class DoubleLeastPostAggregatorTest
{
  @Test
  public void testCompute()
  {
    final String aggName = "rows";
    DoubleLeastPostAggregator leastPostAggregator;
    CountAggregator agg = new CountAggregator();
    agg.aggregate();
    agg.aggregate();
    agg.aggregate();
    Map<String, Object> metricValues = new HashMap<>();
    metricValues.put(aggName, agg.get());

    List<PostAggregator> postAggregatorList =
        Lists.newArrayList(
            new ConstantPostAggregator(
                "roku", 6D
            ),
            new FieldAccessPostAggregator(
                "rows", aggName
            )
        );

    leastPostAggregator = new DoubleLeastPostAggregator("least", postAggregatorList);
    Assert.assertEquals(3.0, leastPostAggregator.compute(metricValues));
  }

  @Test
  public void testComparator()
  {
    final String aggName = "rows";
    DoubleLeastPostAggregator leastPostAggregator;
    CountAggregator agg = new CountAggregator();
    Map<String, Object> metricValues = new HashMap<>();
    metricValues.put(aggName, agg.get());

    List<PostAggregator> postAggregatorList =
        Lists.newArrayList(
            new ConstantPostAggregator(
                "roku", 2D
            ),
            new FieldAccessPostAggregator(
                "rows", aggName
            )
        );

    leastPostAggregator = new DoubleLeastPostAggregator("least", postAggregatorList);
    Comparator comp = leastPostAggregator.getComparator();
    Object before = leastPostAggregator.compute(metricValues);
    agg.aggregate();
    agg.aggregate();
    agg.aggregate();
    metricValues.put(aggName, agg.get());
    Object after = leastPostAggregator.compute(metricValues);

    Assert.assertEquals(-1, comp.compare(before, after));
    Assert.assertEquals(0, comp.compare(before, before));
    Assert.assertEquals(0, comp.compare(after, after));
    Assert.assertEquals(1, comp.compare(after, before));
  }

  @Test
  public void testResultArraySignature()
  {
    final TimeseriesQuery query =
        Druids.newTimeseriesQueryBuilder()
              .dataSource("dummy")
              .intervals("2000/3000")
              .granularity(Granularities.HOUR)
              .aggregators(
                  new CountAggregatorFactory("count")
              )
              .postAggregators(
                  new DoubleLeastPostAggregator(
                      "a",
                      ImmutableList.of(
                          new ConstantPostAggregator("_a", 3L),
                          new ConstantPostAggregator("_b", 1.0f),
                          new ConstantPostAggregator("_c", 5.0)
                      )
                  )
              )
              .build();

    Assert.assertEquals(
        RowSignature.builder()
                    .addTimeColumn()
                    .add("count", ColumnType.LONG)
                    .add("a", ColumnType.DOUBLE)
                    .build(),
        new TimeseriesQueryQueryToolChest().resultArraySignature(query)
    );
  }
}
