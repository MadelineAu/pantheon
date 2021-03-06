/*
 * Copyright 2019 ConsenSys AG.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 */
package tech.pegasys.pantheon.ethereum.eth.sync;

import static java.util.concurrent.CompletableFuture.completedFuture;

import tech.pegasys.pantheon.ethereum.ProtocolContext;
import tech.pegasys.pantheon.ethereum.eth.manager.EthContext;
import tech.pegasys.pantheon.ethereum.eth.manager.EthPeer;
import tech.pegasys.pantheon.ethereum.eth.manager.task.WaitForPeerTask;
import tech.pegasys.pantheon.ethereum.eth.sync.state.SyncState;
import tech.pegasys.pantheon.ethereum.eth.sync.state.SyncTarget;
import tech.pegasys.pantheon.ethereum.eth.sync.tasks.DetermineCommonAncestorTask;
import tech.pegasys.pantheon.ethereum.mainnet.ProtocolSchedule;
import tech.pegasys.pantheon.metrics.MetricsSystem;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public abstract class SyncTargetManager<C> {

  private static final Logger LOG = LogManager.getLogger();
  private volatile long syncTargetDisconnectListenerId;
  private volatile boolean syncTargetDisconnected = false;
  private final SynchronizerConfiguration config;
  private final ProtocolSchedule<C> protocolSchedule;
  private final ProtocolContext<C> protocolContext;
  private final EthContext ethContext;
  private final SyncState syncState;
  private final MetricsSystem metricsSystem;

  public SyncTargetManager(
      final SynchronizerConfiguration config,
      final ProtocolSchedule<C> protocolSchedule,
      final ProtocolContext<C> protocolContext,
      final EthContext ethContext,
      final SyncState syncState,
      final MetricsSystem metricsSystem) {
    this.config = config;
    this.protocolSchedule = protocolSchedule;
    this.protocolContext = protocolContext;
    this.ethContext = ethContext;
    this.syncState = syncState;
    this.metricsSystem = metricsSystem;
  }

  public CompletableFuture<SyncTarget> findSyncTarget() {
    return syncState
        .syncTarget()
        .map(CompletableFuture::completedFuture) // Return an existing sync target if present
        .orElseGet(this::selectNewSyncTarget);
  }

  private CompletableFuture<SyncTarget> selectNewSyncTarget() {
    return selectBestAvailableSyncTarget()
        .thenCompose(
            maybeBestPeer -> {
              if (maybeBestPeer.isPresent()) {
                final EthPeer bestPeer = maybeBestPeer.get();
                return DetermineCommonAncestorTask.create(
                        protocolSchedule,
                        protocolContext,
                        ethContext,
                        bestPeer,
                        config.downloaderHeaderRequestSize(),
                        metricsSystem)
                    .run()
                    .handle(
                        (result, error) -> {
                          if (error != null) {
                            LOG.debug("Failed to find common ancestor", error);
                          }
                          return result;
                        })
                    .thenCompose(
                        (target) -> {
                          if (target == null) {
                            return waitForPeerAndThenSetSyncTarget();
                          }
                          final SyncTarget syncTarget = syncState.setSyncTarget(bestPeer, target);
                          LOG.info(
                              "Found common ancestor with peer {} at block {}",
                              bestPeer,
                              target.getNumber());
                          syncTargetDisconnectListenerId =
                              bestPeer.subscribeDisconnect(this::onSyncTargetPeerDisconnect);
                          return completedFuture(syncTarget);
                        })
                    .thenCompose(
                        syncTarget ->
                            finalizeSelectedSyncTarget(syncTarget)
                                .map(CompletableFuture::completedFuture)
                                .orElseGet(this::waitForPeerAndThenSetSyncTarget));
              } else {
                return waitForPeerAndThenSetSyncTarget();
              }
            });
  }

  protected Optional<SyncTarget> finalizeSelectedSyncTarget(final SyncTarget syncTarget) {
    return Optional.of(syncTarget);
  }

  protected abstract CompletableFuture<Optional<EthPeer>> selectBestAvailableSyncTarget();

  private CompletableFuture<SyncTarget> waitForPeerAndThenSetSyncTarget() {
    return waitForNewPeer().handle((r, t) -> r).thenCompose((r) -> findSyncTarget());
  }

  private CompletableFuture<?> waitForNewPeer() {
    return ethContext
        .getScheduler()
        .timeout(WaitForPeerTask.create(ethContext, metricsSystem), Duration.ofSeconds(5));
  }

  private void onSyncTargetPeerDisconnect(final EthPeer ethPeer) {
    LOG.info("Sync target disconnected: {}", ethPeer);
    syncTargetDisconnected = true;
  }

  public boolean isSyncTargetDisconnected() {
    return syncTargetDisconnected;
  }

  public void clearSyncTarget(final SyncTarget syncTarget) {
    syncTarget.peer().unsubscribeDisconnect(syncTargetDisconnectListenerId);
    syncTargetDisconnected = false;
  }

  public abstract boolean shouldSwitchSyncTarget(final SyncTarget currentTarget);

  public abstract boolean shouldContinueDownloading();
}
