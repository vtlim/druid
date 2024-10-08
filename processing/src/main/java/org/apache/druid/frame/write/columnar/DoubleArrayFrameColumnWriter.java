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

package org.apache.druid.frame.write.columnar;

import org.apache.datasketches.memory.WritableMemory;
import org.apache.druid.frame.allocation.MemoryAllocator;
import org.apache.druid.segment.ColumnValueSelector;

/**
 * Columnar frame writer for {@link org.apache.druid.segment.column.ColumnType#DOUBLE_ARRAY} columns
 */
public class DoubleArrayFrameColumnWriter extends NumericArrayFrameColumnWriter
{
  public DoubleArrayFrameColumnWriter(
      ColumnValueSelector selector,
      MemoryAllocator allocator
  )
  {
    super(selector, allocator, FrameColumnWriters.TYPE_DOUBLE_ARRAY);
  }

  @Override
  int elementSizeBytes()
  {
    return Double.BYTES;
  }

  @Override
  void putNull(WritableMemory memory, long offset)
  {
    memory.putDouble(offset, 0d);
  }

  @Override
  void putArrayElement(WritableMemory memory, long offset, Number element)
  {
    memory.putDouble(offset, element.doubleValue());
  }
}
