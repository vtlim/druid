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

package org.apache.druid.query.groupby.epinephelinae.vector;

import org.apache.datasketches.memory.WritableMemory;
import org.apache.druid.query.groupby.ResultRow;
import org.apache.druid.query.groupby.epinephelinae.collection.MemoryPointer;

/**
 * Treats all rows as null.
 */
public class NilGroupByVectorColumnSelector implements GroupByVectorColumnSelector
{
  public static final NilGroupByVectorColumnSelector INSTANCE = new NilGroupByVectorColumnSelector();

  private NilGroupByVectorColumnSelector()
  {
    // Singleton.
  }

  @Override
  public int getGroupingKeySize()
  {
    return 0;
  }

  @Override
  public int getValueCardinality()
  {
    return 1;
  }

  @Override
  public int writeKeys(WritableMemory keySpace, int keySize, int keyOffset, int startRow, int endRow)
  {
    // Nothing to do.
    return 0;
  }

  @Override
  public void writeKeyToResultRow(MemoryPointer keyMemory, int keyOffset, ResultRow resultRow, int resultRowPosition)
  {
    resultRow.set(resultRowPosition, null);
  }

  @Override
  public void reset()
  {
    // Nothing to do.
  }
}
