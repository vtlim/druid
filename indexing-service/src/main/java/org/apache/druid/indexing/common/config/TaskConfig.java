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

package org.apache.druid.indexing.common.config;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.apache.druid.common.config.Configs;
import org.apache.druid.common.utils.IdUtils;
import org.apache.druid.segment.loading.StorageLocationConfig;
import org.apache.druid.segment.realtime.appenderator.TaskDirectory;
import org.joda.time.Period;

import javax.annotation.Nullable;
import java.io.File;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.List;

/**
 * Configurations for ingestion tasks. These configurations can be applied per middleManager, indexer, or overlord.
 * <p>
 * See {@link org.apache.druid.indexing.overlord.config.DefaultTaskConfig} if you want to apply the same configuration
 * to all tasks submitted to the overlord.
 */
public class TaskConfig implements TaskDirectory
{
  public static final String ALLOW_HADOOP_TASK_EXECUTION_KEY = "druid.indexer.task.allowHadoopTaskExecution";
  private static final Period DEFAULT_DIRECTORY_LOCK_TIMEOUT = new Period("PT10M");
  private static final Period DEFAULT_GRACEFUL_SHUTDOWN_TIMEOUT = new Period("PT5M");
  private static final boolean DEFAULT_STORE_EMPTY_COLUMNS = true;
  private static final long DEFAULT_TMP_STORAGE_BYTES_PER_TASK = -1;

  @JsonProperty
  private final String baseDir;

  @JsonProperty
  private final File baseTaskDir;

  @JsonProperty
  private final boolean restoreTasksOnRestart;

  @JsonProperty
  private final Period gracefulShutdownTimeout;

  @JsonProperty
  private final Period directoryLockTimeout;

  @JsonProperty
  private final List<StorageLocationConfig> shuffleDataLocations;

  @JsonProperty
  private final boolean ignoreTimestampSpecForDruidInputSource;

  @JsonProperty
  private final boolean storeEmptyColumns;

  @JsonProperty
  private final boolean encapsulatedTask;

  @JsonProperty
  private final long tmpStorageBytesPerTask;

  @JsonProperty
  private final boolean allowHadoopTaskExecution;

  @JsonCreator
  public TaskConfig(
      @JsonProperty("baseDir") String baseDir,
      @JsonProperty("baseTaskDir") String baseTaskDir,
      @JsonProperty("restoreTasksOnRestart") boolean restoreTasksOnRestart,
      @JsonProperty("gracefulShutdownTimeout") Period gracefulShutdownTimeout,
      @JsonProperty("directoryLockTimeout") Period directoryLockTimeout,
      @JsonProperty("shuffleDataLocations") List<StorageLocationConfig> shuffleDataLocations,
      @JsonProperty("ignoreTimestampSpecForDruidInputSource") boolean ignoreTimestampSpecForDruidInputSource,
      @JsonProperty("storeEmptyColumns") @Nullable Boolean storeEmptyColumns,
      @JsonProperty("encapsulatedTask") boolean enableTaskLevelLogPush,
      @JsonProperty("tmpStorageBytesPerTask") @Nullable Long tmpStorageBytesPerTask,
      @JsonProperty("allowHadoopTaskExecution") boolean allowHadoopTaskExecution
  )
  {
    this.baseDir = Configs.valueOrDefault(baseDir, System.getProperty("java.io.tmpdir"));
    this.baseTaskDir = new File(defaultDir(baseTaskDir, "persistent/task"));
    this.restoreTasksOnRestart = restoreTasksOnRestart;
    this.gracefulShutdownTimeout = Configs.valueOrDefault(
        gracefulShutdownTimeout,
        DEFAULT_GRACEFUL_SHUTDOWN_TIMEOUT
    );
    this.directoryLockTimeout = Configs.valueOrDefault(
        directoryLockTimeout,
        DEFAULT_DIRECTORY_LOCK_TIMEOUT
    );
    this.shuffleDataLocations = Configs.valueOrDefault(
        shuffleDataLocations,
        Collections.singletonList(
            new StorageLocationConfig(new File(defaultDir(null, "intermediary-segments")), null, null)
        )
    );

    this.ignoreTimestampSpecForDruidInputSource = ignoreTimestampSpecForDruidInputSource;
    this.encapsulatedTask = enableTaskLevelLogPush;

    this.storeEmptyColumns = Configs.valueOrDefault(storeEmptyColumns, DEFAULT_STORE_EMPTY_COLUMNS);
    this.tmpStorageBytesPerTask = Configs.valueOrDefault(tmpStorageBytesPerTask, DEFAULT_TMP_STORAGE_BYTES_PER_TASK);
    this.allowHadoopTaskExecution = allowHadoopTaskExecution;
  }

  private TaskConfig(
      String baseDir,
      File baseTaskDir,
      boolean restoreTasksOnRestart,
      Period gracefulShutdownTimeout,
      Period directoryLockTimeout,
      List<StorageLocationConfig> shuffleDataLocations,
      boolean ignoreTimestampSpecForDruidInputSource,
      boolean storeEmptyColumns,
      boolean encapsulatedTask,
      long tmpStorageBytesPerTask,
      boolean allowHadoopTaskExecution
  )
  {
    this.baseDir = baseDir;
    this.baseTaskDir = baseTaskDir;
    this.restoreTasksOnRestart = restoreTasksOnRestart;
    this.gracefulShutdownTimeout = gracefulShutdownTimeout;
    this.directoryLockTimeout = directoryLockTimeout;
    this.shuffleDataLocations = shuffleDataLocations;
    this.ignoreTimestampSpecForDruidInputSource = ignoreTimestampSpecForDruidInputSource;
    this.storeEmptyColumns = storeEmptyColumns;
    this.encapsulatedTask = encapsulatedTask;
    this.tmpStorageBytesPerTask = tmpStorageBytesPerTask;
    this.allowHadoopTaskExecution = allowHadoopTaskExecution;
  }

  @JsonProperty
  public String getBaseDir()
  {
    return baseDir;
  }

  @JsonProperty
  public File getBaseTaskDir()
  {
    return baseTaskDir;
  }

  @Override
  public File getTaskDir(String taskId)
  {
    return new File(baseTaskDir, IdUtils.validateId("task ID", taskId));
  }

  @Override
  public File getTaskWorkDir(String taskId)
  {
    return new File(getTaskDir(taskId), "work");
  }

  @Override
  public File getTaskLogFile(String taskId)
  {
    return new File(getTaskDir(taskId), "log");
  }

  @Override
  public File getTaskTempDir(String taskId)
  {
    return new File(getTaskDir(taskId), "temp");
  }

  @Override
  public File getTaskLockFile(String taskId)
  {
    return new File(getTaskDir(taskId), "lock");
  }

  @JsonProperty
  public boolean isRestoreTasksOnRestart()
  {
    return restoreTasksOnRestart;
  }

  @JsonProperty
  public Period getGracefulShutdownTimeout()
  {
    return gracefulShutdownTimeout;
  }

  @JsonProperty
  public Period getDirectoryLockTimeout()
  {
    return directoryLockTimeout;
  }

  @JsonProperty
  public List<StorageLocationConfig> getShuffleDataLocations()
  {
    return shuffleDataLocations;
  }

  @JsonProperty
  public boolean isIgnoreTimestampSpecForDruidInputSource()
  {
    return ignoreTimestampSpecForDruidInputSource;
  }

  @JsonProperty
  public boolean isStoreEmptyColumns()
  {
    return storeEmptyColumns;
  }

  @JsonProperty
  public boolean isEncapsulatedTask()
  {
    return encapsulatedTask;
  }

  @JsonProperty
  public long getTmpStorageBytesPerTask()
  {
    return tmpStorageBytesPerTask;
  }

  @JsonProperty
  public boolean isAllowHadoopTaskExecution()
  {
    return allowHadoopTaskExecution;
  }

  private String defaultDir(@Nullable String configParameter, final String defaultVal)
  {
    if (configParameter == null) {
      return Paths.get(getBaseDir(), defaultVal).toString();
    }

    return configParameter;
  }

  public TaskConfig withBaseTaskDir(File baseTaskDir)
  {
    return new TaskConfig(
        baseDir,
        baseTaskDir,
        restoreTasksOnRestart,
        gracefulShutdownTimeout,
        directoryLockTimeout,
        shuffleDataLocations,
        ignoreTimestampSpecForDruidInputSource,
        storeEmptyColumns,
        encapsulatedTask,
        tmpStorageBytesPerTask,
        allowHadoopTaskExecution
    );
  }

  public TaskConfig withTmpStorageBytesPerTask(long tmpStorageBytesPerTask)
  {
    return new TaskConfig(
        baseDir,
        baseTaskDir,
        restoreTasksOnRestart,
        gracefulShutdownTimeout,
        directoryLockTimeout,
        shuffleDataLocations,
        ignoreTimestampSpecForDruidInputSource,
        storeEmptyColumns,
        encapsulatedTask,
        tmpStorageBytesPerTask,
        allowHadoopTaskExecution
    );
  }
}
