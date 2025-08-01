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

package org.apache.druid.query.expression;

import org.apache.druid.error.InvalidInput;
import org.apache.druid.java.util.common.DateTimes;
import org.apache.druid.java.util.common.IAE;
import org.apache.druid.java.util.common.granularity.PeriodGranularity;
import org.apache.druid.math.expr.Expr;
import org.joda.time.Chronology;
import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;
import org.joda.time.Period;
import org.joda.time.chrono.ISOChronology;

import javax.annotation.Nullable;

public class ExprUtils
{
  static DateTimeZone toTimeZone(final Expr timeZoneArg)
  {
    if (!timeZoneArg.isLiteral()) {
      throw new IAE("Time zone must be a literal");
    }

    final Object literalValue = timeZoneArg.getLiteralValue();
    return literalValue == null ? DateTimeZone.UTC : DateTimes.inferTzFromString((String) literalValue);
  }

  static PeriodGranularity toPeriodGranularity(
      final Expr wrappingExpr,
      final Expr periodArg,
      @Nullable final Expr originArg,
      @Nullable final Expr timeZoneArg,
      final Expr.ObjectBinding bindings
  )
  {
    final Period period;
    try {
      period = new Period(periodArg.eval(bindings).asString());
    }
    catch (IllegalArgumentException iae) {
      throw InvalidInput.exception(
          "Invalid period[%s] specified for expression[%s]: [%s]",
          periodArg.stringify(), 
          wrappingExpr.stringify(),
          iae.getMessage()
      );
    }
    final DateTime origin;
    final DateTimeZone timeZone;

    if (timeZoneArg == null) {
      timeZone = null;
    } else {
      final String value = timeZoneArg.eval(bindings).asString();
      timeZone = value != null ? DateTimes.inferTzFromString(value) : null;
    }

    if (originArg == null) {
      origin = null;
    } else {
      Chronology chronology = timeZone == null ? ISOChronology.getInstanceUTC() : ISOChronology.getInstance(timeZone);
      final Object value = originArg.eval(bindings).value();
      origin = value != null ? new DateTime(value, chronology) : null;
    }

    return new PeriodGranularity(period, origin, timeZone);
  }

  /**
   * True if Expr is a non-null string literal.
   */
  static boolean isStringLiteral(final Expr expr)
  {
    return expr.isLiteral() && expr.getLiteralValue() instanceof String;
  }
}
