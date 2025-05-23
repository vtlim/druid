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

package org.apache.druid.sql.calcite.schema;

import com.google.common.collect.Sets;
import com.google.inject.Inject;
import org.apache.druid.client.InternalQueryConfig;
import org.apache.druid.client.ServerView;
import org.apache.druid.client.TimelineServerView;
import org.apache.druid.client.coordinator.CoordinatorClient;
import org.apache.druid.common.guava.FutureUtils;
import org.apache.druid.guice.ManageLifecycle;
import org.apache.druid.java.util.common.Stopwatch;
import org.apache.druid.java.util.common.lifecycle.LifecycleStart;
import org.apache.druid.java.util.common.lifecycle.LifecycleStop;
import org.apache.druid.java.util.emitter.EmittingLogger;
import org.apache.druid.java.util.emitter.service.ServiceEmitter;
import org.apache.druid.segment.column.RowSignature;
import org.apache.druid.segment.metadata.AbstractSegmentMetadataCache;
import org.apache.druid.segment.metadata.CentralizedDatasourceSchemaConfig;
import org.apache.druid.segment.metadata.DataSourceInformation;
import org.apache.druid.segment.metadata.Metric;
import org.apache.druid.segment.realtime.appenderator.SegmentSchemas;
import org.apache.druid.server.QueryLifecycleFactory;
import org.apache.druid.server.coordination.DruidServerMetadata;
import org.apache.druid.server.security.Escalator;
import org.apache.druid.sql.calcite.table.DatasourceTable.PhysicalDatasourceMetadata;
import org.apache.druid.timeline.DataSegment;
import org.apache.druid.timeline.SegmentId;

import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Broker-side cache of segment metadata that combines segments to build
 * datasources which become "tables" in Calcite. This cache provides the "physical"
 * metadata about a dataSource which is blended with catalog "logical" metadata
 * to provide the final user-view of each dataSource.
 * <p>
 * This class extends {@link AbstractSegmentMetadataCache} and introduces following changes,
 * <ul>
 *   <li>The refresh mechanism includes polling the coordinator for datasource schema,
 *       and falling back to running {@link org.apache.druid.query.metadata.metadata.SegmentMetadataQuery}.</li>
 *   <li>It builds and caches {@link PhysicalDatasourceMetadata} object for the table schema</li>
 * </ul>
 */
@ManageLifecycle
public class BrokerSegmentMetadataCache extends AbstractSegmentMetadataCache<PhysicalDatasourceMetadata>
{
  private static final EmittingLogger log = new EmittingLogger(BrokerSegmentMetadataCache.class);

  private final PhysicalDatasourceMetadataFactory dataSourceMetadataFactory;
  private final CoordinatorClient coordinatorClient;

  private final BrokerSegmentMetadataCacheConfig config;
  private final CentralizedDatasourceSchemaConfig centralizedDatasourceSchemaConfig;

  @Inject
  public BrokerSegmentMetadataCache(
      final QueryLifecycleFactory queryLifecycleFactory,
      final TimelineServerView serverView,
      final BrokerSegmentMetadataCacheConfig config,
      final Escalator escalator,
      final InternalQueryConfig internalQueryConfig,
      final ServiceEmitter emitter,
      final PhysicalDatasourceMetadataFactory dataSourceMetadataFactory,
      final CoordinatorClient coordinatorClient,
      final CentralizedDatasourceSchemaConfig centralizedDatasourceSchemaConfig
  )
  {
    super(
        queryLifecycleFactory,
        config,
        escalator,
        internalQueryConfig,
        emitter
    );
    this.dataSourceMetadataFactory = dataSourceMetadataFactory;
    this.coordinatorClient = coordinatorClient;
    this.config = config;
    this.centralizedDatasourceSchemaConfig = centralizedDatasourceSchemaConfig;
    initServerViewTimelineCallback(serverView);
  }

  private void initServerViewTimelineCallback(final TimelineServerView serverView)
  {
    serverView.registerTimelineCallback(
        callbackExec,
        new TimelineServerView.TimelineCallback()
        {
          @Override
          public ServerView.CallbackAction timelineInitialized()
          {
            synchronized (lock) {
              isServerViewInitialized = true;
              lock.notifyAll();
            }

            return ServerView.CallbackAction.CONTINUE;
          }

          @Override
          public ServerView.CallbackAction segmentAdded(final DruidServerMetadata server, final DataSegment segment)
          {
            addSegment(server, segment);
            return ServerView.CallbackAction.CONTINUE;
          }

          @Override
          public ServerView.CallbackAction segmentRemoved(final DataSegment segment)
          {
            removeSegment(segment);
            return ServerView.CallbackAction.CONTINUE;
          }

          @Override
          public ServerView.CallbackAction serverSegmentRemoved(
              final DruidServerMetadata server,
              final DataSegment segment
          )
          {
            removeServerSegment(server, segment);
            return ServerView.CallbackAction.CONTINUE;
          }

          @Override
          public ServerView.CallbackAction segmentSchemasAnnounced(SegmentSchemas segmentSchemas)
          {
            return ServerView.CallbackAction.CONTINUE;
          }
        }
    );
  }

  @LifecycleStart
  @Override
  public void start() throws InterruptedException
  {
    log.info("Initializing cache.");
    cacheExec.submit((Runnable) this::cacheExecLoop);
    if (config.isAwaitInitializationOnStart()) {
      awaitInitialization();
    }
  }

  @LifecycleStop
  @Override
  public void stop()
  {
    cacheExec.shutdownNow();
    callbackExec.shutdownNow();
  }

  /**
   * Execute refresh on the broker in each cycle if CentralizedDatasourceSchema is enabled
   * else if there are segments or datasources to be refreshed.
   */
  @Override
  protected boolean shouldRefresh()
  {
    return centralizedDatasourceSchemaConfig.isEnabled() || super.shouldRefresh();
  }

  /**
   * Refreshes the set of segments in two steps:
   * <ul>
   *  <li>Polls the coordinator for the datasource schema.</li>
   *  <li>Refreshes the remaining set of segments by executing a SegmentMetadataQuery and
   *      builds datasource schema by combining segment schema.</li>
   * </ul>
   *
   * @param segmentsToRefresh    segments for which the schema might have changed
   * @param dataSourcesToRebuild datasources for which the schema might have changed
   * @throws IOException         when querying segment schema from data nodes and tasks
   */
  @Override
  public void refresh(final Set<SegmentId> segmentsToRefresh, final Set<String> dataSourcesToRebuild) throws IOException
  {
    // query schema for all datasources in the inventory,
    // which includes,
    // datasources explicitly marked for rebuilding
    // datasources for the segments to be refreshed
    // prebuilt datasources
    // segmentMetadataInfo keys should be a superset of all other sets including datasources to refresh
    final Set<String> dataSourcesToQuery = new HashSet<>(segmentMetadataInfo.keySet());

    // this is the complete set of datasources polled from the Coordinator
    final Set<String> polledDatasources = queryDataSources();

    dataSourcesToQuery.addAll(polledDatasources);

    log.debug("Querying schema for [%s] datasources from Coordinator.", dataSourcesToQuery);

    // Fetch datasource information from the Coordinator
    Map<String, PhysicalDatasourceMetadata> polledDataSourceMetadata = queryDataSourceInformation(dataSourcesToQuery);

    log.debug("Fetched schema for [%s] datasources from Coordinator.", polledDataSourceMetadata.keySet());

    // update datasource metadata in the cache
    polledDataSourceMetadata.forEach(this::updateDSMetadata);

    // Remove segments of the datasource from refresh list for which we received schema from the Coordinator.
    segmentsToRefresh.removeIf(segmentId -> polledDataSourceMetadata.containsKey(segmentId.getDataSource()));

    Set<SegmentId> refreshed = new HashSet<>();

    // Refresh the remaining segments.
    if (!config.isDisableSegmentMetadataQueries()) {
      refreshed = refreshSegments(segmentsToRefresh);
    }

    synchronized (lock) {
      // Add missing segments back to the refresh list.
      segmentsNeedingRefresh.addAll(Sets.difference(segmentsToRefresh, refreshed));

      // Compute the list of datasources to rebuild tables for.
      dataSourcesToRebuild.addAll(dataSourcesNeedingRebuild);
      refreshed.forEach(segment -> dataSourcesToRebuild.add(segment.getDataSource()));

      // Remove those datasource for which we received schema from the Coordinator.
      dataSourcesToRebuild.removeAll(polledDataSourceMetadata.keySet());

      dataSourcesNeedingRebuild.clear();
    }

    // Rebuild the datasources.
    for (String dataSource : dataSourcesToRebuild) {
      final RowSignature rowSignature = buildDataSourceRowSignature(dataSource);
      if (rowSignature == null) {
        log.info("datasource [%s] no longer exists, all metadata removed.", dataSource);
        tables.remove(dataSource);
        continue;
      }

      if (rowSignature.getColumnNames().isEmpty()) {
        // this case could arise when metadata refresh is disabled on broker
        // and a new datasource is added
        log.info("datasource [%s] schema has not been initialized yet, "
                 + "check coordinator logs if this message is persistent.", dataSource);
        // this is a harmless call
        tables.remove(dataSource);
        continue;
      }

      final PhysicalDatasourceMetadata physicalDatasourceMetadata = dataSourceMetadataFactory.build(dataSource, rowSignature);
      updateDSMetadata(dataSource, physicalDatasourceMetadata);
    }
  }

  @Override
  protected void removeSegmentAction(SegmentId segmentId)
  {
    // noop, no additional action needed when segment is removed.
  }

  private Set<String> queryDataSources()
  {
    Set<String> dataSources = new HashSet<>();

    try {
      Set<String> polled = FutureUtils.getUnchecked(coordinatorClient.fetchDataSourcesWithUsedSegments(), true);
      if (polled != null) {
        dataSources.addAll(polled);
      }
    }
    catch (Exception e) {
      log.debug(e, "Failed to query datasources from the Coordinator.");
    }

    return dataSources;
  }

  private Map<String, PhysicalDatasourceMetadata> queryDataSourceInformation(Set<String> dataSourcesToQuery)
  {
    final Stopwatch stopwatch = Stopwatch.createStarted();

    List<DataSourceInformation> dataSourceInformations = null;

    try {
      dataSourceInformations = FutureUtils.getUnchecked(coordinatorClient.fetchDataSourceInformation(dataSourcesToQuery), true);
    }
    catch (Exception e) {
      log.debug(e, "Failed to query datasource information from the Coordinator.");
      emitMetric(Metric.BROKER_POLL_FAILED, 1);
    }

    emitMetric(Metric.BROKER_POLL_DURATION_MILLIS, stopwatch.millisElapsed());

    final Map<String, PhysicalDatasourceMetadata> polledDataSourceMetadata = new HashMap<>();

    if (dataSourceInformations != null) {
      dataSourceInformations.forEach(dataSourceInformation -> polledDataSourceMetadata.put(
          dataSourceInformation.getDataSource(),
          dataSourceMetadataFactory.build(
              dataSourceInformation.getDataSource(),
              dataSourceInformation.getRowSignature()
          )
      ));
    }

    return polledDataSourceMetadata;
  }

  private void updateDSMetadata(String dataSource, PhysicalDatasourceMetadata physicalDatasourceMetadata)
  {
    final PhysicalDatasourceMetadata oldTable = tables.put(dataSource, physicalDatasourceMetadata);
    if (oldTable == null || !oldTable.getRowSignature().equals(physicalDatasourceMetadata.getRowSignature())) {
      log.info("[%s] has new signature: %s.", dataSource, physicalDatasourceMetadata.getRowSignature());
    } else {
      log.debug("[%s] signature is unchanged.", dataSource);
    }
  }
}
