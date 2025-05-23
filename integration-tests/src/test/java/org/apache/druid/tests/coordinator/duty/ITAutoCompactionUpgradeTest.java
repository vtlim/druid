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

package org.apache.druid.tests.coordinator.duty;

import com.google.inject.Inject;
import org.apache.druid.data.input.MaxSizeSplitHintSpec;
import org.apache.druid.indexer.partitions.DynamicPartitionsSpec;
import org.apache.druid.indexer.partitions.PartitionsSpec;
import org.apache.druid.java.util.common.granularity.Granularities;
import org.apache.druid.server.coordinator.DataSourceCompactionConfig;
import org.apache.druid.server.coordinator.DruidCompactionConfig;
import org.apache.druid.server.coordinator.InlineSchemaDataSourceCompactionConfig;
import org.apache.druid.server.coordinator.UserCompactionTaskGranularityConfig;
import org.apache.druid.server.coordinator.UserCompactionTaskIOConfig;
import org.apache.druid.server.coordinator.UserCompactionTaskQueryTuningConfig;
import org.apache.druid.testing.IntegrationTestingConfig;
import org.apache.druid.testing.clients.CompactionResourceTestClient;
import org.apache.druid.testing.guice.DruidTestModuleFactory;
import org.apache.druid.tests.TestNGGroup;
import org.apache.druid.tests.indexer.AbstractIndexerTest;
import org.joda.time.Period;
import org.testng.Assert;
import org.testng.annotations.Guice;
import org.testng.annotations.Test;

@Test(groups = {TestNGGroup.UPGRADE})
@Guice(moduleFactory = DruidTestModuleFactory.class)
public class ITAutoCompactionUpgradeTest extends AbstractIndexerTest
{
  private static final String UPGRADE_DATASOURCE_NAME = "upgradeTest";

  @Inject
  protected CompactionResourceTestClient compactionResource;

  @Inject
  private IntegrationTestingConfig config;

  @Test
  public void testUpgradeAutoCompactionConfigurationWhenConfigurationFromOlderVersionAlreadyExist() throws Exception
  {
    // Verify that compaction config already exist. This config was inserted manually into the database using SQL script.
    // This auto compaction configuration payload is from Druid 0.21.0
    DruidCompactionConfig coordinatorCompactionConfig = DruidCompactionConfig.empty()
        .withDatasourceConfigs(compactionResource.getAllCompactionConfigs());
    DataSourceCompactionConfig foundDataSourceCompactionConfig
        = coordinatorCompactionConfig.findConfigForDatasource(UPGRADE_DATASOURCE_NAME).orNull();
    Assert.assertNotNull(foundDataSourceCompactionConfig);

    // Now submit a new auto compaction configuration
    PartitionsSpec newPartitionsSpec = new DynamicPartitionsSpec(4000, null);
    Period newSkipOffset = Period.seconds(0);

    DataSourceCompactionConfig compactionConfig = InlineSchemaDataSourceCompactionConfig
        .builder()
        .forDataSource(UPGRADE_DATASOURCE_NAME)
        .withSkipOffsetFromLatest(newSkipOffset)
        .withTuningConfig(
            new UserCompactionTaskQueryTuningConfig(
                null,
                null,
                null,
                null,
                new MaxSizeSplitHintSpec(null, 1),
                newPartitionsSpec,
                null,
                null,
                null,
                null,
                null,
                1,
                null,
                null,
                null,
                null,
                null,
                1,
                null
            )
        )
        .withGranularitySpec(
            new UserCompactionTaskGranularityConfig(Granularities.YEAR, null, null)
        )
        .withIoConfig(new UserCompactionTaskIOConfig(true))
        .build();
    compactionResource.submitCompactionConfig(compactionConfig);

    // Verify that compaction was successfully updated
    foundDataSourceCompactionConfig
        = compactionResource.getDataSourceCompactionConfig(UPGRADE_DATASOURCE_NAME);
    Assert.assertNotNull(foundDataSourceCompactionConfig);
    Assert.assertNotNull(foundDataSourceCompactionConfig.getTuningConfig());
    Assert.assertEquals(foundDataSourceCompactionConfig.getTuningConfig().getPartitionsSpec(), newPartitionsSpec);
    Assert.assertEquals(foundDataSourceCompactionConfig.getSkipOffsetFromLatest(), newSkipOffset);
  }
}
