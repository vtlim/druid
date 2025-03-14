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

package org.apache.druid.math.expr.vector;

import org.apache.druid.math.expr.Expr;

/**
 * common machinery for processing two input operators and functions, which should always treat null inputs as null
 * output, and are backed by a primitive values instead of an object values (and need to use the null vectors instead of
 * checking the vector themselves for nulls)
 *
 * this one is specialized for producing long[], see {@link DoubleBivariateFunctionVectorProcessor} for
 * double[] primitives.
 */
public abstract class LongBivariateFunctionVectorProcessor<TLeftInput, TRightInput>
    implements ExprVectorProcessor<long[]>
{
  final ExprVectorProcessor<TLeftInput> left;
  final ExprVectorProcessor<TRightInput> right;
  final boolean[] outNulls;
  final long[] outValues;

  protected LongBivariateFunctionVectorProcessor(
      ExprVectorProcessor<TLeftInput> left,
      ExprVectorProcessor<TRightInput> right
  )
  {
    this.left = left;
    this.right = right;
    this.outValues = new long[left.maxVectorSize()];
    this.outNulls = new boolean[left.maxVectorSize()];
  }

  @Override
  public final ExprEvalVector<long[]> evalVector(Expr.VectorInputBinding bindings)
  {
    final ExprEvalVector<TLeftInput> lhs = left.evalVector(bindings);
    final ExprEvalVector<TRightInput> rhs = right.evalVector(bindings);

    final int currentSize = bindings.getCurrentVectorSize();
    final boolean[] leftNulls = lhs.getNullVector();
    final boolean[] rightNulls = rhs.getNullVector();
    final boolean hasLeftNulls = leftNulls != null;
    final boolean hasRightNulls = rightNulls != null;
    final boolean hasNulls = hasLeftNulls || hasRightNulls;

    final TLeftInput leftInput = lhs.values();
    final TRightInput rightInput = rhs.values();

    if (hasNulls) {
      for (int i = 0; i < currentSize; i++) {
        outNulls[i] = (hasLeftNulls && leftNulls[i]) || (hasRightNulls && rightNulls[i]);
        if (!outNulls[i]) {
          processIndex(leftInput, rightInput, i);
        } else {
          outValues[i] = 0L;
        }
      }
    } else {
      for (int i = 0; i < currentSize; i++) {
        processIndex(leftInput, rightInput, i);
        outNulls[i] = false;
      }
    }
    return asEval();
  }

  abstract void processIndex(TLeftInput leftInput, TRightInput rightInput, int i);

  final ExprEvalVector<long[]> asEval()
  {
    return new ExprEvalLongVector(outValues, outNulls);
  }

  @Override
  public int maxVectorSize()
  {
    return outValues.length;
  }
}
