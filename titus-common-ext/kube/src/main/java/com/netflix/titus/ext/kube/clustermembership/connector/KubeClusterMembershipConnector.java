/*
 * Copyright 2019 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.netflix.titus.ext.kube.clustermembership.connector;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import javax.annotation.PreDestroy;
import javax.inject.Singleton;

import com.netflix.titus.api.clustermembership.connector.ClusterMembershipConnector;
import com.netflix.titus.api.clustermembership.model.ClusterMember;
import com.netflix.titus.api.clustermembership.model.ClusterMemberLeadership;
import com.netflix.titus.api.clustermembership.model.ClusterMembershipRevision;
import com.netflix.titus.api.clustermembership.model.event.ClusterMembershipChangeEvent;
import com.netflix.titus.api.clustermembership.model.event.ClusterMembershipEvent;
import com.netflix.titus.api.clustermembership.model.event.LeaderElectionChangeEvent;
import com.netflix.titus.common.framework.simplereconciler.SimpleReconciliationEngine;
import com.netflix.titus.common.runtime.TitusRuntime;
import com.netflix.titus.common.util.IOExt;
import com.netflix.titus.common.util.rx.ReactorExt;
import com.netflix.titus.ext.kube.clustermembership.connector.action.KubeLeaderElectionActions;
import com.netflix.titus.ext.kube.clustermembership.connector.action.KubeRegistrationActions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

@Singleton
public class KubeClusterMembershipConnector implements ClusterMembershipConnector {

    private static final Logger logger = LoggerFactory.getLogger(KubeClusterMembershipConnector.class);

    private final Scheduler scheduler;

    private final SimpleReconciliationEngine<KubeClusterState> reconciler;
    private final KubeContext context;

    private final Disposable membershipSubscription;
    private final Disposable leaderElectionSubscription;
    private final Disposable reconcilerEventsSubscription;

    public KubeClusterMembershipConnector(ClusterMember initial,
                                          KubeMembershipExecutor kubeMembershipExecutor,
                                          KubeLeaderElectionExecutor kubeLeaderElectionExecutor,
                                          KubeConnectorConfiguration configuration,
                                          TitusRuntime titusRuntime) {
        this.scheduler = Schedulers.newSingle("ClusterMembershipReconciler");

        this.context = new KubeContext(kubeMembershipExecutor, kubeLeaderElectionExecutor, titusRuntime);
        this.reconciler = SimpleReconciliationEngine.<KubeClusterState>newBuilder("LeaderElection")
                .withInitial(new KubeClusterState(initial, configuration, titusRuntime.getClock()))
                .withReconcilerActionsProvider(new KubeClusterMembershipStateReconciler(context, configuration))
                .withQuickCycle(Duration.ofMillis(configuration.getReconcilerQuickCycleMs()))
                .withLongCycle(Duration.ofMillis(configuration.getReconcilerLongCycleMs()))
                .withScheduler(scheduler)
                .withTitusRuntime(titusRuntime)
                .build();

        Duration reconnectInterval = Duration.ofMillis(configuration.getKubeReconnectIntervalMs());
        this.membershipSubscription = kubeMembershipExecutor.watchMembershipEvents()
                .onErrorResume(e -> {
                            logger.info("Reconnecting membership event stream from Kubernetes terminated with an error: {}", e.getMessage());
                            logger.debug("Stack trace", e);
                            return Flux.just(ClusterMembershipEvent.disconnectedEvent(e))
                                    .concatWith(Flux.interval(reconnectInterval).take(1).flatMap(tick -> kubeMembershipExecutor.watchMembershipEvents()));
                        }
                )
                .subscribe(
                        event -> {
                            if (event instanceof ClusterMembershipChangeEvent) {
                                reconciler.apply(Mono.just(currentState -> currentState.processMembershipEventStreamEvent((ClusterMembershipChangeEvent) event)))
                                        .subscribe(
                                                next -> logger.info("Processed Kubernetes event: {}", event),
                                                e -> logger.warn("Kubernetes event processing failure", e)
                                        );
                            }
                        },
                        e -> logger.error("Unexpected error in the Kubernetes membership event stream", e),
                        () -> logger.info("Membership Kubernetes event stream closed")
                );
        this.leaderElectionSubscription = kubeLeaderElectionExecutor.watchLeaderElectionProcessUpdates()
                .onErrorResume(e -> {
                            logger.info("Reconnecting leadership event stream from Kubernetes terminated with an error: {}", e.getMessage());
                            logger.debug("Stack trace", e);
                            return Flux.just(ClusterMembershipEvent.disconnectedEvent(e))
                                    .concatWith(Flux.interval(reconnectInterval).take(1).flatMap(tick -> kubeLeaderElectionExecutor.watchLeaderElectionProcessUpdates()));
                        }
                )
                .subscribe(
                        event -> {
                            if (event instanceof LeaderElectionChangeEvent) {
                                reconciler.apply(Mono.just(currentState -> currentState.processLeaderElectionEventStreamEvent((LeaderElectionChangeEvent) event)))
                                        .subscribe(
                                                next -> logger.debug("Processed Kubernetes event: {}", event),
                                                e -> logger.warn("Kubernetes event processing failure", e)
                                        );
                            }
                        },
                        e -> logger.error("Unexpected error in the Kubernetes membership event stream", e),
                        () -> logger.info("Membership Kubernetes event stream closed")
                );

        this.reconcilerEventsSubscription = this.reconciler.changes().subscribe(
                next -> logger.debug("Reconciler update: {}", next.getDeltaEvents()),
                e -> logger.warn("Reconciler event stream terminated with an error", e),
                () -> logger.warn("Reconciler event stream completed")
        );
    }

    @PreDestroy
    public void shutdown() {
        IOExt.closeSilently(reconciler);
        ReactorExt.safeDispose(scheduler, membershipSubscription, leaderElectionSubscription, reconcilerEventsSubscription);
    }

    @Override
    public ClusterMembershipRevision<ClusterMember> getLocalClusterMemberRevision() {
        return reconciler.getCurrent().getLocalMemberRevision();
    }

    @Override
    public Map<String, ClusterMembershipRevision<ClusterMember>> getClusterMemberSiblings() {
        return reconciler.getCurrent().getNotStaleClusterMemberSiblings();
    }

    @Override
    public ClusterMembershipRevision<ClusterMemberLeadership> getLocalLeadershipRevision() {
        return reconciler.getCurrent().getLocalMemberLeadershipRevision();
    }

    @Override
    public Optional<ClusterMembershipRevision<ClusterMemberLeadership>> findCurrentLeader() {
        return reconciler.getCurrent().findCurrentLeader();
    }

    @Override
    public Mono<ClusterMembershipRevision<ClusterMember>> register(Function<ClusterMember, ClusterMembershipRevision<ClusterMember>> selfUpdate) {
        return reconciler.apply(Mono.defer(() ->
                KubeRegistrationActions.registerLocal(context, reconciler.getCurrent(), selfUpdate))
        ).map(KubeClusterState::getLocalMemberRevision);
    }

    @Override
    public Mono<ClusterMembershipRevision<ClusterMember>> unregister(Function<ClusterMember, ClusterMembershipRevision<ClusterMember>> selfUpdate) {
        return reconciler.apply(Mono.defer(() ->
                KubeRegistrationActions.unregisterLocal(context, reconciler.getCurrent(), selfUpdate))
        ).map(KubeClusterState::getLocalMemberRevision);
    }

    @Override
    public Mono<Void> joinLeadershipGroup() {
        return reconciler.apply(KubeLeaderElectionActions.createJoinLeadershipGroupAction(context)).ignoreElement().cast(Void.class);
    }

    @Override
    public Mono<Boolean> leaveLeadershipGroup(boolean onlyNonLeader) {
        return reconciler.apply(KubeLeaderElectionActions.createLeaveLeadershipGroupAction(context, onlyNonLeader))
                .map(currentState -> !currentState.isInLeaderElectionProcess());
    }

    @Override
    public Flux<ClusterMembershipEvent> membershipChangeEvents() {
        return Flux.defer(() -> {
            AtomicBoolean firstEmit = new AtomicBoolean(true);
            return reconciler.changes()
                    .flatMap(update -> firstEmit.getAndSet(false)
                            ? Flux.just(update.getSnapshotEvent())
                            : Flux.fromIterable(update.getDeltaEvents())
                    );
        });
    }
}
