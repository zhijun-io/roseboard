package com.roseboard.infrastructure.cluster;

import com.roseboard.infrastructure.transport.TransportMessageTypes;
import org.springframework.stereotype.Component;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

@Component
public class ClusterPartitionCoordinator {
    private final ClusterSettings settings;
    private final ClusterNodeDiscovery discovery;
    private final CorePartitionManager corePartitionManager;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "cluster-partition-refresh");
        thread.setDaemon(true);
        return thread;
    });
    private volatile ScheduledFuture<?> pendingRefresh;

    public ClusterPartitionCoordinator(ClusterSettings settings,
                                       ClusterNodeDiscovery discovery,
                                       CorePartitionManager corePartitionManager) {
        this.settings = settings;
        this.discovery = discovery;
        this.corePartitionManager = corePartitionManager;
    }

    public void scheduleRefresh() {
        long delayMs = Math.max(0L, settings.getRecalculateDelayMs());
        if (delayMs == 0L) {
            refresh();
            return;
        }
        ScheduledFuture<?> pending = pendingRefresh;
        if (pending != null && !pending.isDone()) {
            return;
        }
        pendingRefresh = scheduler.schedule(this::refresh, delayMs, TimeUnit.MILLISECONDS);
    }

    public void refresh() {
        discovery.heartbeat();
        corePartitionManager.refresh(TransportMessageTypes.MAIN_TOPIC);
    }
}
