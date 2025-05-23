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

package org.apache.druid.timeline;

import com.google.common.collect.Iterators;

import java.util.Comparator;
import java.util.Iterator;

/**
 * {@link VersionedIntervalTimeline} for {@link DataSegment} objects.
 */
public class SegmentTimeline extends VersionedIntervalTimeline<String, DataSegment>
{
  public static SegmentTimeline forSegments(Iterable<DataSegment> segments)
  {
    return forSegments(segments.iterator());
  }

  public static SegmentTimeline forSegments(Iterator<DataSegment> segments)
  {
    final SegmentTimeline timeline = new SegmentTimeline();
    timeline.addSegments(segments);
    return timeline;
  }

  public SegmentTimeline()
  {
    super(Comparator.naturalOrder());
  }

  public void addSegments(Iterator<DataSegment> segments)
  {
    addAll(
        Iterators.transform(
            segments,
            segment -> new PartitionChunkEntry<>(
                segment.getInterval(),
                segment.getVersion(),
                segment.getShardSpec().createChunk(segment)
            )
        )
    );
  }

  public void add(DataSegment segment)
  {
    add(segment.getInterval(), segment.getVersion(), segment.getShardSpec().createChunk(segment));
  }

  public void remove(DataSegment segment)
  {
    remove(segment.getInterval(), segment.getVersion(), segment.getShardSpec().createChunk(segment));
  }

  public boolean isOvershadowed(DataSegment segment)
  {
    return isOvershadowed(segment.getInterval(), segment.getVersion(), segment);
  }

}
