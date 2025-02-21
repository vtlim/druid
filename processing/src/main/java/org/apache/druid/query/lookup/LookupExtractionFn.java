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

package org.apache.druid.query.lookup;


import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.Function;
import org.apache.druid.java.util.common.StringUtils;
import org.apache.druid.query.extraction.ExtractionCacheHelper;
import org.apache.druid.query.extraction.FunctionalExtraction;

import javax.annotation.Nullable;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

public class LookupExtractionFn extends FunctionalExtraction
{
  private final LookupExtractor lookup;
  private final boolean optimize;

  @JsonCreator
  public LookupExtractionFn(
      @JsonProperty("lookup") final LookupExtractor lookup,
      @JsonProperty("retainMissingValue") final boolean retainMissingValue,
      @JsonProperty("replaceMissingValueWith") @Nullable final String replaceMissingValueWith,
      @JsonProperty("injective") @Nullable final Boolean injective,
      @JsonProperty("optimize") @Nullable final Boolean optimize
  )
  {
    super(
        new Function<>()
        {
          @Nullable
          @Override
          public String apply(@Nullable String input)
          {
            return lookup.apply(input);
          }
        },
        retainMissingValue,
        replaceMissingValueWith,
        injective != null ? injective : lookup.isOneToOne()
    );
    this.lookup = lookup;
    this.optimize = optimize == null ? true : optimize;
  }


  @JsonProperty
  public LookupExtractor getLookup()
  {
    return lookup;
  }

  @Override
  @JsonProperty
  public boolean isRetainMissingValue()
  {
    return super.isRetainMissingValue();
  }

  @Override
  @JsonProperty
  public String getReplaceMissingValueWith()
  {
    return super.getReplaceMissingValueWith();
  }

  @Override
  @JsonProperty
  public boolean isInjective()
  {
    return super.isInjective();
  }

  @JsonProperty("optimize")
  public boolean isOptimize()
  {
    return optimize;
  }

  @Override
  public byte[] getCacheKey()
  {
    try {
      final ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
      outputStream.write(ExtractionCacheHelper.CACHE_TYPE_ID_LOOKUP);
      outputStream.write(lookup.getCacheKey());
      if (getReplaceMissingValueWith() != null) {
        outputStream.write(StringUtils.toUtf8(getReplaceMissingValueWith()));
        outputStream.write(0xFF);
      }
      outputStream.write(isInjective() ? 1 : 0);
      outputStream.write(isRetainMissingValue() ? 1 : 0);
      outputStream.write(isOptimize() ? 1 : 0);
      return outputStream.toByteArray();
    }
    catch (IOException ex) {
      // If ByteArrayOutputStream.write has problems, that is a very bad thing
      throw new RuntimeException(ex);
    }
  }

  @Override
  public boolean equals(Object o)
  {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }

    LookupExtractionFn that = (LookupExtractionFn) o;

    if (isOptimize() != that.isOptimize()) {
      return false;
    }
    if (isRetainMissingValue() != that.isRetainMissingValue()) {
      return false;
    }
    if (isInjective() != that.isInjective()) {
      return false;
    }
    if (getLookup() != null ? !getLookup().equals(that.getLookup()) : that.getLookup() != null) {
      return false;
    }
    return getReplaceMissingValueWith() != null
           ? getReplaceMissingValueWith().equals(that.getReplaceMissingValueWith())
           : that.getReplaceMissingValueWith() == null;

  }

  @Override
  public int hashCode()
  {
    int result = getLookup() != null ? getLookup().hashCode() : 0;
    result = 31 * result + (isOptimize() ? 1 : 0);
    result = 31 * result + (isRetainMissingValue() ? 1 : 0);
    result = 31 * result + (getReplaceMissingValueWith() != null ? getReplaceMissingValueWith().hashCode() : 0);
    result = 31 * result + (isInjective() ? 1 : 0);
    return result;
  }

  @Override
  public String toString()
  {
    return "LookupExtractionFn{" +
           "lookup=" + lookup +
           ", optimize=" + optimize +
           ", retainMissingValue=" + isRetainMissingValue() +
           ", replaceMissingValueWith='" + getReplaceMissingValueWith() + '\'' +
           ", injective=" + isInjective() +
           '}';
  }
}
