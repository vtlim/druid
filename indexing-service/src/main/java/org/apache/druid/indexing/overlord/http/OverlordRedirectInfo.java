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

package org.apache.druid.indexing.overlord.http;

import com.google.common.base.Optional;
import com.google.common.collect.ImmutableSet;
import com.google.inject.Inject;
import org.apache.druid.indexing.overlord.DruidOverlord;
import org.apache.druid.java.util.common.StringUtils;
import org.apache.druid.server.http.RedirectInfo;

import java.net.URL;
import java.util.Set;

/**
 */
public class OverlordRedirectInfo implements RedirectInfo
{
  private static final Set<String> LOCAL_PATHS = ImmutableSet.of(
      "/druid/indexer/v1/leader",
      "/druid/indexer/v1/isLeader"
  );

  private final DruidOverlord overlord;

  @Inject
  public OverlordRedirectInfo(DruidOverlord overlord)
  {
    this.overlord = overlord;
  }

  @Override
  public boolean doLocal(String requestURI)
  {
    return (requestURI != null && LOCAL_PATHS.contains(requestURI)) || overlord.isLeader();
  }

  @Override
  public URL getRedirectURL(String queryString, String requestURI)
  {
    try {
      final Optional<String> redirectLocation = overlord.getRedirectLocation();
      if (!redirectLocation.isPresent()) {
        return null;
      }

      String location = StringUtils.format("%s%s", redirectLocation.get(), requestURI);
      if (queryString != null) {
        location = StringUtils.format("%s?%s", location, queryString);
      }

      return new URL(location);
    }
    catch (Exception e) {
      throw new RuntimeException(e);
    }
  }
}
