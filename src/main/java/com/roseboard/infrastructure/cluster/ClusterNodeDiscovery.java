package com.roseboard.infrastructure.cluster;

import java.util.List;

/** Shared cluster node registry for Transport and Core partition coordination. */
public interface ClusterNodeDiscovery {

    void register();

    void heartbeat();

    void deregister();

    List<String> sortedNodes();
}
