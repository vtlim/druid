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

package org.apache.druid.client;

import com.google.common.base.Predicate;
import org.apache.druid.java.util.common.Pair;
import org.apache.druid.server.coordination.DruidServerMetadata;
import org.apache.druid.timeline.DataSegment;

import java.util.concurrent.Executor;

public interface FilteredServerInventoryView extends InventoryView
{
  void registerSegmentCallback(
      Executor exec,
      ServerView.SegmentCallback callback,
      Predicate<Pair<DruidServerMetadata, DataSegment>> filter
  );

  void registerServerCallback(Executor exec, ServerView.ServerCallback callback);
}
