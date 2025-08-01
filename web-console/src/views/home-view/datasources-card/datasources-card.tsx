/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import React from 'react';

import { getConsoleViewIcon } from '../../../druid-models';
import type { Capabilities } from '../../../helpers';
import { useQueryManager } from '../../../hooks';
import { getApiArray, pluralIfNeeded, queryDruidSql } from '../../../utils';
import { HomeViewCard } from '../home-view-card/home-view-card';

export interface DatasourcesCardProps {
  capabilities: Capabilities;
}

export const DatasourcesCard = React.memo(function DatasourcesCard(props: DatasourcesCardProps) {
  const [datasourceCountState] = useQueryManager<Capabilities, number>({
    initQuery: props.capabilities,
    processQuery: async (capabilities, cancelToken) => {
      let datasources: string[];
      if (capabilities.hasSql()) {
        datasources = await queryDruidSql(
          {
            query: `SELECT datasource FROM sys.segments GROUP BY 1`,
          },
          cancelToken,
        );
      } else if (capabilities.hasCoordinatorAccess()) {
        datasources = await getApiArray<string>('/druid/coordinator/v1/datasources', cancelToken);
      } else {
        throw new Error(`must have SQL or coordinator access`);
      }

      return datasources.length;
    },
  });

  return (
    <HomeViewCard
      className="datasources-card"
      href="#datasources"
      icon={getConsoleViewIcon('datasources')}
      title="Datasources"
      loading={datasourceCountState.loading}
      error={datasourceCountState.error}
    >
      <p>{pluralIfNeeded(datasourceCountState.data || 0, 'datasource')}</p>
    </HomeViewCard>
  );
});
