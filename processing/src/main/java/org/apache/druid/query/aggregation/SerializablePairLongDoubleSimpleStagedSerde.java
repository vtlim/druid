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

package org.apache.druid.query.aggregation;

import org.apache.druid.segment.column.TypeStrategies;

import javax.annotation.Nullable;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public class SerializablePairLongDoubleSimpleStagedSerde extends AbstractSerializablePairLongObjectSimpleStagedSerde<SerializablePairLongDouble>
{
  public SerializablePairLongDoubleSimpleStagedSerde()
  {
    super(SerializablePairLongDouble.class);
  }

  @Nullable
  @Override
  public SerializablePairLongDouble deserialize(ByteBuffer byteBuffer)
  {
    if (byteBuffer.remaining() == 0) {
      return null;
    }

    ByteBuffer readOnlyBuffer = byteBuffer.asReadOnlyBuffer().order(ByteOrder.nativeOrder());
    long lhs = readOnlyBuffer.getLong();

    Double rhs = null;
    if (readOnlyBuffer.get() == TypeStrategies.IS_NOT_NULL_BYTE) {
      rhs = readOnlyBuffer.getDouble();
    }

    return new SerializablePairLongDouble(lhs, rhs);
  }
}
