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

package org.apache.druid.segment.incremental;

import com.google.common.base.Function;
import com.google.common.base.Joiner;
import com.google.common.base.Predicate;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import org.apache.druid.data.input.InputRow;
import org.apache.druid.data.input.Row;
import org.apache.druid.data.input.impl.SpatialDimensionSchema;
import org.apache.druid.java.util.common.ISE;
import org.joda.time.DateTime;

import javax.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * We throw away all invalid spatial dimensions
 */
public class SpatialDimensionRowTransformer implements Function<InputRow, InputRow>
{
  private static final Joiner JOINER = Joiner.on(",");
  private static final Splitter SPLITTER = Splitter.on(",");

  private final Map<String, SpatialDimensionSchema> spatialDimensionMap;
  private final Set<String> spatialPartialDimNames;

  public SpatialDimensionRowTransformer(List<SpatialDimensionSchema> spatialDimensions)
  {
    this.spatialDimensionMap = new HashMap<>();
    for (SpatialDimensionSchema spatialDimension : spatialDimensions) {
      if (this.spatialDimensionMap.put(spatialDimension.getDimName(), spatialDimension) != null) {
        throw new ISE("Duplicate spatial dimension names found! Check your schema yo!");
      }
    }
    this.spatialPartialDimNames = Sets.newHashSet(
        Iterables.concat(
            Lists.transform(
                spatialDimensions,
                new Function<SpatialDimensionSchema, List<String>>()
                {
                  @Override
                  public List<String> apply(SpatialDimensionSchema input)
                  {
                    return input.getDims();
                  }
                }
            )
        )
    );
  }

  @Override
  public InputRow apply(final InputRow row)
  {
    final Map<String, List<String>> spatialLookup = new HashMap<>();

    // remove all spatial dimensions
    final List<String> finalDims = Lists.newArrayList(
        Iterables.filter(
            row.getDimensions(),
            new Predicate<>()
            {
              @Override
              public boolean apply(String input)
              {
                return !spatialDimensionMap.containsKey(input) && !spatialPartialDimNames.contains(input);
              }
            }
        )
    );

    InputRow retVal = new InputRow()
    {
      @Override
      public List<String> getDimensions()
      {
        return finalDims;
      }

      @Override
      public long getTimestampFromEpoch()
      {
        return row.getTimestampFromEpoch();
      }

      @Override
      public DateTime getTimestamp()
      {
        return row.getTimestamp();
      }

      @Override
      public List<String> getDimension(String dimension)
      {
        List<String> retVal = spatialLookup.get(dimension);
        return (retVal == null) ? row.getDimension(dimension) : retVal;
      }

      @Override
      public Object getRaw(String dimension)
      {
        List<String> retVal = spatialLookup.get(dimension);
        return (retVal == null) ? row.getRaw(dimension) : retVal;
      }

      @Override
      public Number getMetric(String metric)
      {
        return row.getMetric(metric);
      }

      @Override
      public String toString()
      {
        return row.toString();
      }

      @Override
      public int compareTo(Row o)
      {
        return getTimestamp().compareTo(o.getTimestamp());
      }
    };

    for (Map.Entry<String, SpatialDimensionSchema> entry : spatialDimensionMap.entrySet()) {
      final String spatialDimName = entry.getKey();
      final SpatialDimensionSchema spatialDim = entry.getValue();

      List<String> dimVals = row.getDimension(spatialDimName);
      if (dimVals != null && !dimVals.isEmpty()) {
        if (dimVals.size() != 1) {
          throw new ISE("Spatial dimension value must be in an array!");
        }
        if (isJoinedSpatialDimValValid(dimVals.get(0))) {
          spatialLookup.put(spatialDimName, dimVals);
          finalDims.add(spatialDimName);
        }
      } else {
        List<String> spatialDimVals = new ArrayList<>();
        for (String dim : spatialDim.getDims()) {
          List<String> partialDimVals = row.getDimension(dim);
          if (isSpatialDimValsValid(partialDimVals)) {
            spatialDimVals.addAll(partialDimVals);
          }
        }

        if (spatialDimVals.size() == spatialDim.getDims().size()) {
          spatialLookup.put(spatialDimName, Collections.singletonList(JOINER.join(spatialDimVals)));
          finalDims.add(spatialDimName);
        }
      }
    }

    return retVal;
  }

  private boolean isSpatialDimValsValid(List<String> dimVals)
  {
    if (dimVals == null || dimVals.isEmpty()) {
      return false;
    }
    for (String dimVal : dimVals) {
      if (tryParseFloat(dimVal) == null) {
        return false;
      }
    }
    return true;
  }

  private boolean isJoinedSpatialDimValValid(String dimVal)
  {
    if (dimVal == null || dimVal.isEmpty()) {
      return false;
    }
    Iterable<String> dimVals = SPLITTER.split(dimVal);
    for (String val : dimVals) {
      if (tryParseFloat(val) == null) {
        return false;
      }
    }
    return true;
  }

  @Nullable
  private static Float tryParseFloat(String val)
  {
    if (val == null) {
      return null;
    }

    try {
      return Float.parseFloat(val);
    }
    catch (NumberFormatException e) {
      return null;
    }
  }

  /**
   * Decodes encodedCoordinate.
   *
   * @param encodedCoordinate encoded coordinate
   *
   * @return decoded coordinate, or null if it could not be decoded
   */
  @Nullable
  public static float[] decode(final String encodedCoordinate)
  {
    if (encodedCoordinate == null) {
      return null;
    }

    final ImmutableList<String> parts = ImmutableList.copyOf(SPLITTER.split(encodedCoordinate));
    final float[] coordinate = new float[parts.size()];

    for (int i = 0; i < coordinate.length; i++) {
      final Float floatPart = tryParseFloat(parts.get(i));
      if (floatPart == null) {
        return null;
      } else {
        coordinate[i] = floatPart;
      }
    }

    return coordinate;
  }
}
