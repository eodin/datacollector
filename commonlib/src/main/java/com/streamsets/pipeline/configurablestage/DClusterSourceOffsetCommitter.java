/**
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
package com.streamsets.pipeline.configurablestage;

import java.util.List;
import java.util.Map;

import com.streamsets.pipeline.api.impl.ClusterSource;
import com.streamsets.pipeline.api.Source;
import com.streamsets.pipeline.api.Stage;
import com.streamsets.pipeline.api.impl.Utils;
import com.streamsets.pipeline.lib.util.ThreadUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class DClusterSourceOffsetCommitter extends DSourceOffsetCommitter implements ClusterSource {
  private static final Logger LOG = LoggerFactory.getLogger(DClusterSourceOffsetCommitter.class);
  private ClusterSource clusterSource;

  @Override
  Stage<Source.Context> createStage() {
    Stage<Source.Context> result = super.createStage();
    LOG.info("Created source of type: {}", source);
    if (source instanceof ClusterSource) {
      clusterSource = (ClusterSource) source;
    } else if (source == null) {
      throw new NullPointerException("Source cannot be null");
    }
    return result;
  }

  @Override
  public String getName() {
    initializeClusterSource();
    return clusterSource.getName();
  }

  @Override
  public boolean isInBatchMode() {
    initializeClusterSource();
    return clusterSource.isInBatchMode();
  }

  /**
   * Writes batch of data to the source
   * @param batch
   * @throws InterruptedException
   */
  @Override
  public void put(List<Map.Entry> batch) throws InterruptedException {
    initializeClusterSource();
    clusterSource.put(batch);
  }

  private boolean initializeClusterSource() {
    // TODO fix this hack and ensure initialization is synchronous
    long start = System.currentTimeMillis();
    while (clusterSource == null && ThreadUtil.sleep(1) && (System.currentTimeMillis() - start) < 60L * 1000L) {
      // Get actual source in case of source being a delegating source (DelegatingKafkaSource)
      Source source = getSource();
      if (source instanceof ClusterSource) {
        clusterSource = (ClusterSource) source;
        return true;
      } else if (source != null) {
        LOG.info(Utils.format(
          "The instance '{}' will not call this method as it does not implement '{}'", source.getClass().getName(),
          ClusterSource.class.getName()));
        return false;
      }
    }
    if (clusterSource == null) {
      throw new RuntimeException("Could not obtain cluster source");
    }
    return true;
  }

  /**
   * Return the no of records produced by this source
   * @return
   */
  @Override
  public long getRecordsProduced() {
    if (initializeClusterSource()) {
      return clusterSource.getRecordsProduced();
    } else {
      return -1;
    }

  }

  /**
   * Return true if a unrecoverable error has occured
   * @return
   */
  @Override
  public boolean inErrorState() {
    if (initializeClusterSource()) {
      return clusterSource.inErrorState();
    } else {
      return false;
    }
  }

  @Override
  public Map<String, String> getConfigsToShip() {
    if (initializeClusterSource()) {
      return clusterSource.getConfigsToShip();
    } else {
      return null;
    }
  }

  @Override
  public void postDestroy() {
    if (initializeClusterSource()) {
      clusterSource.postDestroy();
    }
  }

}