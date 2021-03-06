/**
 * Copyright 2017 LinkedIn Corp. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 */
package com.github.ambry.store;

import com.github.ambry.config.StoreConfig;
import com.github.ambry.utils.Time;
import com.github.ambry.utils.Utils;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * Responsible for managing compaction of a {@link BlobStore}. V0 implementation returns entire log segment range
 * ignoring those overlapping with {@link Journal} as part of CompactionDetails.
 */
class CompactionManager {
  private final String mountPath;
  private final StoreConfig storeConfig;
  private final Time time;
  private final long messageRetentionTimeInMs;
  private final Collection<BlobStore> stores;
  private final CompactionExecutor compactionExecutor;
  private final Logger logger = LoggerFactory.getLogger(getClass());

  private Thread compactionThread;

  /**
   * Creates a CompactionManager that handles scheduling and executing compaction.
   * @param mountPath the mount path of all the stores for which compaction may be executed.
   * @param storeConfig the {@link StoreConfig} that contains configurationd details.
   * @param stores the {@link Collection} of {@link BlobStore} that compaction needs to be executed for.
   * @param time the {@link Time} instance to use.
   */
  CompactionManager(String mountPath, StoreConfig storeConfig, Collection<BlobStore> stores, Time time) {
    this.mountPath = mountPath;
    this.storeConfig = storeConfig;
    this.stores = stores;
    this.time = time;
    this.messageRetentionTimeInMs = storeConfig.storeDeletedMessageRetentionDays * Time.SecsPerDay * Time.MsPerSec;
    compactionExecutor = storeConfig.storeEnableCompaction ? new CompactionExecutor() : null;
  }

  /**
   * Enables the compaction manager allowing it execute compactions if required.
   */
  void enable() {
    if (compactionExecutor != null) {
      logger.info("Compaction thread started for {}", mountPath);
      compactionThread = Utils.newThread("CompactionThread-" + mountPath, compactionExecutor, true);
      compactionThread.start();
    }
  }

  /**
   * Disables the compaction manager which disallows any new compactions.
   */
  void disable() {
    if (compactionExecutor != null) {
      compactionExecutor.disable();
    }
  }

  /**
   * Awaits the termination of all pending jobs of the compaction manager.
   */
  void awaitTermination() {
    if (compactionExecutor != null) {
      try {
        compactionThread.join(2000);
      } catch (InterruptedException e) {
        logger.error("Compaction thread join wait for {} was interrupted", mountPath);
      }
    }
  }

  /**
   * Get compaction details for a given {@link BlobStore} if any
   * @param blobStore the {@link BlobStore} for which compation details are requested
   * @return the {@link CompactionDetails} containing the details about log segments that needs to be compacted.
   * {@code null} if compaction is not required
   * @throws StoreException when {@link BlobStore} is not started
   */
  CompactionDetails getCompactionDetails(BlobStore blobStore) throws StoreException {
    long usedCapacity = blobStore.getSizeInBytes();
    long totalCapacity = blobStore.getCapacityInBytes();
    CompactionDetails details = null;
    if (usedCapacity >= (storeConfig.storeMinUsedCapacityToTriggerCompactionInPercentage / 100.0) * totalCapacity) {
      List<String> potentialLogSegments = blobStore.getLogSegmentsNotInJournal();
      if (potentialLogSegments != null) {
        details = new CompactionDetails(time.milliseconds() - messageRetentionTimeInMs, potentialLogSegments);
      }
    }
    return details;
  }

  /**
   * A {@link Runnable} that cycles through the stores and executes compaction if required.
   */
  private class CompactionExecutor implements Runnable {
    private final Logger logger = LoggerFactory.getLogger(getClass());
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition waitCondition = lock.newCondition();
    private final Set<BlobStore> storesToSkip = new HashSet<>();
    private final long waitTimeMs = storeConfig.storeCompactionCheckFrequencyInHours * Time.SecsPerHour * Time.MsPerSec;

    private volatile boolean enabled = true;

    /**
     * Starts by resuming any compactions that were left halfway. In steady state, it cycles through the stores at a
     * configurable frequency and runs compaction as required.
     */
    @Override
    public void run() {
      // complete any compactions in progress
      for (BlobStore store : stores) {
        if (store.isStarted()) {
          try {
            store.maybeResumeCompaction();
          } catch (Exception e) {
            logger.error("Compaction of store {} failed on resume. Continuing with the next store", store, e);
            storesToSkip.add(store);
          }
        }
      }
      // continue to do compactions as required.
      while (enabled) {
        try {
          long startTimeMs = time.milliseconds();
          for (BlobStore store : stores) {
            try {
              if (!enabled) {
                break;
              }
              if (store.isStarted() && !storesToSkip.contains(store)) {
                CompactionDetails details = getCompactionDetails(store);
                if (details != null) {
                  store.compact(details);
                }
              }
            } catch (Exception e) {
              logger.error("Compaction of store {} failed. Continuing with the next store", store, e);
              storesToSkip.add(store);
            }
          }
          lock.lock();
          try {
            if (enabled) {
              long timeElapsed = time.milliseconds() - startTimeMs;
              time.await(waitCondition, waitTimeMs - timeElapsed);
            }
          } finally {
            lock.unlock();
          }
        } catch (Exception e) {
          logger.error("Compaction execution encountered an error either during wait. Continuing", e);
        }
      }
    }

    /**
     * Disables the executor by disallowing scheduling of any new compaction jobs.
     */
    void disable() {
      lock.lock();
      try {
        enabled = false;
        waitCondition.signal();
      } finally {
        lock.unlock();
      }
    }
  }
}
