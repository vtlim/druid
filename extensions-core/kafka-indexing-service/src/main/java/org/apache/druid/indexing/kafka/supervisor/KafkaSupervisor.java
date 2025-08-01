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

package org.apache.druid.indexing.kafka.supervisor;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Objects;
import com.google.common.collect.Sets;
import org.apache.druid.common.utils.IdUtils;
import org.apache.druid.data.input.kafka.KafkaRecordEntity;
import org.apache.druid.data.input.kafka.KafkaTopicPartition;
import org.apache.druid.error.DruidException;
import org.apache.druid.indexing.common.task.Task;
import org.apache.druid.indexing.common.task.TaskResource;
import org.apache.druid.indexing.kafka.KafkaDataSourceMetadata;
import org.apache.druid.indexing.kafka.KafkaIndexTask;
import org.apache.druid.indexing.kafka.KafkaIndexTaskClientFactory;
import org.apache.druid.indexing.kafka.KafkaIndexTaskIOConfig;
import org.apache.druid.indexing.kafka.KafkaIndexTaskTuningConfig;
import org.apache.druid.indexing.kafka.KafkaRecordSupplier;
import org.apache.druid.indexing.kafka.KafkaSequenceNumber;
import org.apache.druid.indexing.overlord.DataSourceMetadata;
import org.apache.druid.indexing.overlord.IndexerMetadataStorageCoordinator;
import org.apache.druid.indexing.overlord.TaskMaster;
import org.apache.druid.indexing.overlord.TaskStorage;
import org.apache.druid.indexing.overlord.supervisor.autoscaler.LagStats;
import org.apache.druid.indexing.seekablestream.SeekableStreamEndSequenceNumbers;
import org.apache.druid.indexing.seekablestream.SeekableStreamIndexTask;
import org.apache.druid.indexing.seekablestream.SeekableStreamIndexTaskIOConfig;
import org.apache.druid.indexing.seekablestream.SeekableStreamIndexTaskTuningConfig;
import org.apache.druid.indexing.seekablestream.SeekableStreamSequenceNumbers;
import org.apache.druid.indexing.seekablestream.SeekableStreamStartSequenceNumbers;
import org.apache.druid.indexing.seekablestream.common.OrderedPartitionableRecord;
import org.apache.druid.indexing.seekablestream.common.OrderedSequenceNumber;
import org.apache.druid.indexing.seekablestream.common.RecordSupplier;
import org.apache.druid.indexing.seekablestream.common.StreamException;
import org.apache.druid.indexing.seekablestream.common.StreamPartition;
import org.apache.druid.indexing.seekablestream.supervisor.SeekableStreamSupervisor;
import org.apache.druid.indexing.seekablestream.supervisor.SeekableStreamSupervisorIOConfig;
import org.apache.druid.indexing.seekablestream.supervisor.SeekableStreamSupervisorReportPayload;
import org.apache.druid.java.util.common.StringUtils;
import org.apache.druid.java.util.emitter.EmittingLogger;
import org.apache.druid.segment.incremental.RowIngestionMetersFactory;
import org.joda.time.DateTime;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Supervisor responsible for managing the KafkaIndexTasks for a single dataSource. At a high level, the class accepts a
 * {@link KafkaSupervisorSpec} which includes the Kafka topic and configuration as well as an ingestion spec which will
 * be used to generate the indexing tasks. The run loop periodically refreshes its view of the Kafka topic's partitions
 * and the list of running indexing tasks and ensures that all partitions are being read from and that there are enough
 * tasks to satisfy the desired number of replicas. As tasks complete, new tasks are queued to process the next range of
 * Kafka offsets.
 */
public class KafkaSupervisor extends SeekableStreamSupervisor<KafkaTopicPartition, Long, KafkaRecordEntity>
{
  public static final TypeReference<TreeMap<Integer, Map<KafkaTopicPartition, Long>>> CHECKPOINTS_TYPE_REF =
      new TypeReference<>() {};

  private static final EmittingLogger log = new EmittingLogger(KafkaSupervisor.class);
  private static final Long NOT_SET = -1L;
  private static final Long END_OF_PARTITION = Long.MAX_VALUE;

  private final Pattern pattern;
  private volatile Map<KafkaTopicPartition, Long> latestSequenceFromStream;
  private volatile Map<KafkaTopicPartition, Long> partitionToTimeLag;

  private final KafkaSupervisorSpec spec;

  public KafkaSupervisor(
      final TaskStorage taskStorage,
      final TaskMaster taskMaster,
      final IndexerMetadataStorageCoordinator indexerMetadataStorageCoordinator,
      final KafkaIndexTaskClientFactory taskClientFactory,
      final ObjectMapper mapper,
      final KafkaSupervisorSpec spec,
      final RowIngestionMetersFactory rowIngestionMetersFactory
  )
  {
    super(
        StringUtils.format("KafkaSupervisor-%s", spec.getId()),
        taskStorage,
        taskMaster,
        indexerMetadataStorageCoordinator,
        taskClientFactory,
        mapper,
        spec,
        rowIngestionMetersFactory,
        false
    );

    this.spec = spec;
    this.pattern = getIoConfig().isMultiTopic() ? Pattern.compile(getIoConfig().getStream()) : null;
  }


  @Override
  protected RecordSupplier<KafkaTopicPartition, Long, KafkaRecordEntity> setupRecordSupplier()
  {
    return new KafkaRecordSupplier(
        spec.getIoConfig().getConsumerProperties(),
        sortingMapper,
        spec.getIoConfig().getConfigOverrides(),
        spec.getIoConfig().isMultiTopic()
    );
  }

  @Override
  protected int getTaskGroupIdForPartition(KafkaTopicPartition partitionId)
  {
    Integer taskCount = spec.getIoConfig().getTaskCount();
    if (partitionId.isMultiTopicPartition()) {
      return Math.abs(31 * partitionId.topic().hashCode() + partitionId.partition()) % taskCount;
    } else {
      return partitionId.partition() % taskCount;
    }
  }

  @Override
  protected boolean checkSourceMetadataMatch(DataSourceMetadata metadata)
  {
    return metadata instanceof KafkaDataSourceMetadata;
  }

  @Override
  protected boolean doesTaskMatchSupervisor(Task task)
  {
    if (task instanceof KafkaIndexTask) {
      final String supervisorId = ((KafkaIndexTask) task).getSupervisorId();
      return Objects.equal(supervisorId, spec.getId());
    } else {
      return false;
    }
  }

  @Override
  protected SeekableStreamSupervisorReportPayload<KafkaTopicPartition, Long> createReportPayload(
      int numPartitions,
      boolean includeOffsets
  )
  {
    KafkaSupervisorIOConfig ioConfig = spec.getIoConfig();
    Map<KafkaTopicPartition, Long> partitionLag = getRecordLagPerPartitionInLatestSequences(getHighestCurrentOffsets());
    return new KafkaSupervisorReportPayload(
        spec.getId(),
        spec.getDataSchema().getDataSource(),
        ioConfig.getStream(),
        numPartitions,
        ioConfig.getReplicas(),
        ioConfig.getTaskDuration().getMillis() / 1000,
        includeOffsets ? latestSequenceFromStream : null,
        includeOffsets ? partitionLag : null,
        includeOffsets ? getPartitionTimeLag() : null,
        includeOffsets ? aggregatePartitionLags(partitionLag).getTotalLag() : null,
        includeOffsets ? sequenceLastUpdated : null,
        spec.isSuspended(),
        stateManager.isHealthy(),
        stateManager.getSupervisorState().getBasicState(),
        stateManager.getSupervisorState(),
        stateManager.getExceptionEvents()
    );
  }


  @Override
  protected SeekableStreamIndexTaskIOConfig createTaskIoConfig(
      int groupId,
      Map<KafkaTopicPartition, Long> startPartitions,
      Map<KafkaTopicPartition, Long> endPartitions,
      String baseSequenceName,
      DateTime minimumMessageTime,
      DateTime maximumMessageTime,
      Set<KafkaTopicPartition> exclusiveStartSequenceNumberPartitions,
      SeekableStreamSupervisorIOConfig ioConfig
  )
  {
    KafkaSupervisorIOConfig kafkaIoConfig = (KafkaSupervisorIOConfig) ioConfig;
    return new KafkaIndexTaskIOConfig(
        groupId,
        baseSequenceName,
        null,
        null,
        new SeekableStreamStartSequenceNumbers<>(kafkaIoConfig.getStream(), startPartitions, Collections.emptySet()),
        new SeekableStreamEndSequenceNumbers<>(kafkaIoConfig.getStream(), endPartitions),
        kafkaIoConfig.getConsumerProperties(),
        kafkaIoConfig.getPollTimeout(),
        true,
        minimumMessageTime,
        maximumMessageTime,
        ioConfig.getInputFormat(),
        kafkaIoConfig.getConfigOverrides(),
        kafkaIoConfig.isMultiTopic(),
        ioConfig.getTaskDuration().getStandardMinutes()
    );
  }

  @Override
  protected List<SeekableStreamIndexTask<KafkaTopicPartition, Long, KafkaRecordEntity>> createIndexTasks(
      int replicas,
      String baseSequenceName,
      ObjectMapper sortingMapper,
      TreeMap<Integer, Map<KafkaTopicPartition, Long>> sequenceOffsets,
      SeekableStreamIndexTaskIOConfig taskIoConfig,
      SeekableStreamIndexTaskTuningConfig taskTuningConfig,
      RowIngestionMetersFactory rowIngestionMetersFactory
  ) throws JsonProcessingException
  {
    final String checkpoints = sortingMapper.writerFor(CHECKPOINTS_TYPE_REF).writeValueAsString(sequenceOffsets);
    final Map<String, Object> context = createBaseTaskContexts();
    context.put(CHECKPOINTS_CTX_KEY, checkpoints);

    List<SeekableStreamIndexTask<KafkaTopicPartition, Long, KafkaRecordEntity>> taskList = new ArrayList<>();
    for (int i = 0; i < replicas; i++) {
      String taskId = IdUtils.getRandomIdWithPrefix(baseSequenceName);
      taskList.add(new KafkaIndexTask(
          taskId,
          spec.getId(),
          new TaskResource(baseSequenceName, 1),
          spec.getDataSchema(),
          (KafkaIndexTaskTuningConfig) taskTuningConfig,
          (KafkaIndexTaskIOConfig) taskIoConfig,
          context,
          sortingMapper
      ));
    }
    return taskList;
  }

  @Override
  protected Map<KafkaTopicPartition, Long> getPartitionRecordLag()
  {
    Map<KafkaTopicPartition, Long> highestCurrentOffsets = getHighestCurrentOffsets();

    if (latestSequenceFromStream == null) {
      return null;
    }

    Set<KafkaTopicPartition> kafkaPartitions = latestSequenceFromStream.keySet();
    Set<KafkaTopicPartition> taskPartitions = highestCurrentOffsets.keySet();
    if (!kafkaPartitions.equals(taskPartitions)) {
      try {
        log.warn("Mismatched kafka and task partitions: Missing Task Partitions %s, Missing Kafka Partitions %s",
                sortingMapper.writeValueAsString(Sets.difference(kafkaPartitions, taskPartitions)),
                 sortingMapper.writeValueAsString(Sets.difference(taskPartitions, kafkaPartitions)));
      }
      catch (JsonProcessingException e) {
        throw DruidException.defensive("Failed to serialize KafkaTopicPartition when getting partition record lag: %s",
                                       e.getMessage());
      }
    }

    return getRecordLagPerPartitionInLatestSequences(highestCurrentOffsets);
  }

  @Nullable
  @Override
  protected Map<KafkaTopicPartition, Long> getPartitionTimeLag()
  {
    return partitionToTimeLag;
  }

  // suppress use of CollectionUtils.mapValues() since the valueMapper function is dependent on map key here
  @SuppressWarnings("SSBasedInspection")
  // Used while calculating cummulative lag for entire stream
  private Map<KafkaTopicPartition, Long> getRecordLagPerPartitionInLatestSequences(Map<KafkaTopicPartition, Long> currentOffsets)
  {
    if (latestSequenceFromStream == null) {
      return Collections.emptyMap();
    }

    return latestSequenceFromStream
        .entrySet()
        .stream()
        .collect(
            Collectors.toMap(
                Entry::getKey,
                e -> e.getValue() != null
                     ? e.getValue() - Optional.ofNullable(currentOffsets.get(e.getKey())).orElse(0L)
                     : 0
            )
        );
  }

  @Override
  protected Map<KafkaTopicPartition, Long> getRecordLagPerPartition(Map<KafkaTopicPartition, Long> currentOffsets)
  {
    if (latestSequenceFromStream == null || currentOffsets == null) {
      return Collections.emptyMap();
    }

    return currentOffsets
        .entrySet()
        .stream()
        .filter(e -> latestSequenceFromStream.get(e.getKey()) != null)
        .collect(
            Collectors.toMap(
                Entry::getKey,
                e -> e.getValue() != null
                     ? latestSequenceFromStream.get(e.getKey()) - e.getValue()
                     : 0
            )
        );
  }

  @Override
  protected Map<KafkaTopicPartition, Long> getTimeLagPerPartition(Map<KafkaTopicPartition, Long> currentOffsets)
  {
    // Currently not supported
    return null;
  }

  @Override
  protected KafkaDataSourceMetadata createDataSourceMetaDataForReset(String topic, Map<KafkaTopicPartition, Long> map)
  {
    return new KafkaDataSourceMetadata(new SeekableStreamEndSequenceNumbers<>(topic, map));
  }

  @Override
  protected OrderedSequenceNumber<Long> makeSequenceNumber(Long seq, boolean isExclusive)
  {
    return KafkaSequenceNumber.of(seq);
  }

  @Override
  protected Long getNotSetMarker()
  {
    return NOT_SET;
  }

  @Override
  protected Long getEndOfPartitionMarker()
  {
    return END_OF_PARTITION;
  }

  @Override
  protected boolean isEndOfShard(Long seqNum)
  {
    return false;
  }

  @Override
  protected boolean isShardExpirationMarker(Long seqNum)
  {
    return false;
  }

  @Override
  protected boolean useExclusiveStartSequenceNumberForNonFirstSequence()
  {
    return false;
  }

  @Override
  public LagStats computeLagStats()
  {
    Map<KafkaTopicPartition, Long> partitionRecordLag = getPartitionRecordLag();
    if (partitionRecordLag == null) {
      return new LagStats(0, 0, 0);
    }

    return aggregatePartitionLags(partitionRecordLag);
  }

  /**
   * This method is similar to updatePartitionLagFromStream
   * but also determines time lag. Once this method has been
   * tested, we can remove the older one.
   */
  private void updatePartitionTimeAndRecordLagFromStream()
  {
    final Map<KafkaTopicPartition, Long> highestCurrentOffsets = getHighestCurrentOffsets();

    getRecordSupplierLock().lock();
    try {
      Set<KafkaTopicPartition> partitionIds;
      try {
        partitionIds = recordSupplier.getPartitionIds(getIoConfig().getStream());
      }
      catch (Exception e) {
        log.warn("Could not fetch partitions for topic/stream [%s]", getIoConfig().getStream());
        throw new StreamException(e);
      }

      final Set<StreamPartition<KafkaTopicPartition>> partitions = partitionIds
          .stream()
          .map(e -> new StreamPartition<>(getIoConfig().getStream(), e))
          .collect(Collectors.toSet());

      // Since we cannot compute the current timestamp for partitions for
      // which we haven't started reading yet explictly set them.
      final Set<KafkaTopicPartition> yetToReadPartitions = new HashSet<>();
      for (KafkaTopicPartition partition : partitionIds) {
        Long highestCurrentOffset = highestCurrentOffsets.get(partition);
        if (highestCurrentOffset == null || highestCurrentOffset == 0) {
          yetToReadPartitions.add(partition);
        } else {
          recordSupplier.seek(new StreamPartition<>(getIoConfig().getStream(), partition), highestCurrentOffset - 1);
        }
      }

      final Map<KafkaTopicPartition, Long> lastIngestedTimestamps = getTimestampPerPartitionAtCurrentOffset(partitions);
      // Note: this might give weird values for lag when the tasks are yet to start processing
      yetToReadPartitions.forEach(p -> lastIngestedTimestamps.put(p, 0L));

      recordSupplier.seekToLatest(partitions);
      latestSequenceFromStream = recordSupplier.getLatestSequenceNumbers(partitions);

      for (Map.Entry<KafkaTopicPartition, Long> entry : latestSequenceFromStream.entrySet()) {
        // if there are no messages .getEndOffset would return 0, but if there are n msgs it would return n+1
        // and hence we need to seek to n - 2 to get the nth msg in the next poll.
        if (entry.getValue() != 0) {
          recordSupplier.seek(new StreamPartition<>(getIoConfig().getStream(), entry.getKey()), entry.getValue() - 2);
        }
      }

      partitionToTimeLag = getTimestampPerPartitionAtCurrentOffset(partitions)
          .entrySet().stream().filter(e -> lastIngestedTimestamps.containsKey(e.getKey()))
          .collect(
              Collectors.toMap(
                  Entry::getKey,
                  e -> e.getValue() - lastIngestedTimestamps.get(e.getKey())
              )
          );
    }
    catch (InterruptedException e) {
      throw new StreamException(e);
    }
    finally {
      getRecordSupplierLock().unlock();
    }
  }

  private Map<KafkaTopicPartition, Long> getTimestampPerPartitionAtCurrentOffset(Set<StreamPartition<KafkaTopicPartition>> allPartitions)
  {
    Map<KafkaTopicPartition, Long> result = new HashMap<>();
    Set<StreamPartition<KafkaTopicPartition>> remainingPartitions = new HashSet<>(allPartitions);

    try {
      int maxPolls = 5;
      while (!remainingPartitions.isEmpty() && maxPolls-- > 0) {
        for (OrderedPartitionableRecord<KafkaTopicPartition, Long, KafkaRecordEntity> record : recordSupplier.poll(getIoConfig().getPollTimeout())) {
          if (!result.containsKey(record.getPartitionId())) {
            result.put(record.getPartitionId(), record.getTimestamp());
            remainingPartitions.remove(new StreamPartition<>(getIoConfig().getStream(), record.getPartitionId()));
            if (remainingPartitions.isEmpty()) {
              break;
            }
          }
          recordSupplier.assign(remainingPartitions);
        }
      }
    }
    finally {
      recordSupplier.assign(allPartitions);
    }

    if (!remainingPartitions.isEmpty()) {
      log.info("Could not fetch the latest timestamp for partitions [%s].", remainingPartitions);
    }
    return result;
  }

  /**
   * Fetches the latest offsets from the Kafka stream and updates the map
   * {@link #latestSequenceFromStream}. The actual lag is computed lazily in
   * {@link #getPartitionRecordLag}.
   */
  @Override
  protected void updatePartitionLagFromStream()
  {
    if (getIoConfig().isEmitTimeLagMetrics()) {
      updatePartitionTimeAndRecordLagFromStream();
      return;
    }

    getRecordSupplierLock().lock();
    try {
      Set<KafkaTopicPartition> partitionIds;
      try {
        partitionIds = recordSupplier.getPartitionIds(getIoConfig().getStream());
      }
      catch (Exception e) {
        log.warn("Could not fetch partitions for topic/stream [%s]", getIoConfig().getStream());
        throw new StreamException(e);
      }

      Set<StreamPartition<KafkaTopicPartition>> partitions = partitionIds
          .stream()
          .map(e -> new StreamPartition<>(getIoConfig().getStream(), e))
          .collect(Collectors.toSet());

      recordSupplier.seekToLatest(partitions);

      latestSequenceFromStream =
          partitions.stream().collect(Collectors.toMap(StreamPartition::getPartitionId, recordSupplier::getPosition));
    }
    catch (InterruptedException e) {
      throw new StreamException(e);
    }
    finally {
      getRecordSupplierLock().unlock();
    }
  }

  @Override
  protected Map<KafkaTopicPartition, Long> getLatestSequencesFromStream()
  {
    return latestSequenceFromStream != null ? latestSequenceFromStream : new HashMap<>();
  }

  @Override
  protected String baseTaskName()
  {
    return "index_kafka";
  }

  @Override
  @VisibleForTesting
  public KafkaSupervisorIOConfig getIoConfig()
  {
    return spec.getIoConfig();
  }

  @VisibleForTesting
  public KafkaSupervisorTuningConfig getTuningConfig()
  {
    return spec.getTuningConfig();
  }

  protected boolean isMultiTopic()
  {
    return getIoConfig().isMultiTopic() && pattern != null;
  }

  /**
   * Gets the offsets as stored in the metadata store. The map returned will only contain
   * offsets from topic partitions that match the current supervisor config stream. This
   * override is needed because in the case of multi-topic, a user could have updated the supervisor
   * config from single topic to mult-topic, where the new multi-topic pattern regex matches the
   * old config single topic. Without this override, the previously stored metadata for the single
   * topic would be deemed as different from the currently configure stream, and not be included in
   * the offset map returned. This implementation handles these cases appropriately.
   *
   * @return the previoulsy stored offsets from metadata storage, possibly updated with offsets removed
   * for topics that do not match the currently configured supervisor topic. Topic partition keys may also be
   * updated to single topic or multi-topic depending on the supervisor config, as needed.
   */
  @Override
  protected Map<KafkaTopicPartition, Long> getOffsetsFromMetadataStorage()
  {
    final DataSourceMetadata dataSourceMetadata = retrieveDataSourceMetadata();
    if (checkSourceMetadataMatch(dataSourceMetadata)) {
      @SuppressWarnings("unchecked")
      SeekableStreamSequenceNumbers<KafkaTopicPartition, Long> partitions = ((KafkaDataSourceMetadata) dataSourceMetadata)
          .getSeekableStreamSequenceNumbers();
      if (partitions != null && partitions.getPartitionSequenceNumberMap() != null) {
        Map<KafkaTopicPartition, Long> partitionOffsets = new HashMap<>();
        Set<String> topicMisMatchLogged = new HashSet<>();
        partitions.getPartitionSequenceNumberMap().forEach((kafkaTopicPartition, value) -> {
          final String matchValue;
          // previous offsets are from multi-topic config
          if (kafkaTopicPartition.topic().isPresent()) {
            matchValue = kafkaTopicPartition.topic().get();
          } else {
            // previous offsets are from single topic config
            matchValue = partitions.getStream();
          }

          KafkaTopicPartition matchingTopicPartition = getMatchingKafkaTopicPartition(kafkaTopicPartition, matchValue);

          if (matchingTopicPartition == null && !topicMisMatchLogged.contains(matchValue)) {
            log.warn(
                "Topic/stream in metadata storage [%s] doesn't match spec topic/stream [%s], ignoring stored sequences",
                matchValue,
                getIoConfig().getStream()
            );
            topicMisMatchLogged.add(matchValue);
          }
          if (matchingTopicPartition != null) {
            partitionOffsets.put(matchingTopicPartition, value);
          }
        });
        return partitionOffsets;
      }
    }

    return Collections.emptyMap();
  }

  @Nullable
  private KafkaTopicPartition getMatchingKafkaTopicPartition(
      final KafkaTopicPartition kafkaTopicPartition,
      final String streamMatchValue
  )
  {
    final boolean match;

    match = pattern != null
        ? pattern.matcher(streamMatchValue).matches()
        : getIoConfig().getStream().equals(streamMatchValue);

    return match ? new KafkaTopicPartition(isMultiTopic(), streamMatchValue, kafkaTopicPartition.partition()) : null;
  }
}
