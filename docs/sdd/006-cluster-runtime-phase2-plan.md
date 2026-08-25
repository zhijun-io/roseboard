# Roseboard Cluster Runtime Phase 2 Plan

> **Spec：** [`006-cluster-runtime-phase2-spec.md`](006-cluster-runtime-phase2-spec.md)（已批准）

## Slice A — 统一发现（AC-1, AC-7）

- **Goal：** `ClusterNodeDiscovery` + `roseboard.cluster.*`；Transport 别名 fallback。
- **Test：** `ClusterNodeDiscoveryAliasTest` — transport 与 cluster 配置等价时 node 列表一致。
- **Verify：** `mvn test -Dtest=ClusterNodeDiscoveryAliasTest,ZookeeperTransportClusterDiscoveryTest`

## Slice B — TB_CORE 分区（AC-2, AC-3, AC-4）

- **Goal：** `CorePartitionManager` 发布 `TB_CORE`；`QueueCorePartitionListener` 更新 Main consumer；移除 `TransportMainPartitionListener`。
- **Test：** 迁移 `TransportPartitionManagerTest` → `CorePartitionManagerTest`（TB_CORE 事件）。
- **Verify：** `mvn test -Dtest=CorePartitionManagerTest,TransportQueueIntegrationTest`

## Slice C — Revoke drain（AC-5）

- **Goal：** `PartitionLifecycleListener`；`QueueConsumerManager` revoke 前 await in-flight + commit。
- **Test：** `CorePartitionRevokeIntegrationTest`（Memory 2 节点模拟）。
- **Verify：** `mvn test -Dtest=CorePartitionRevokeIntegrationTest`

## AC 映射

| AC | Slice |
|----|-------|
| AC-1 | A |
| AC-2 | B |
| AC-3 | B |
| AC-4 | B |
| AC-5 | C |
| AC-6 | A+B（默认 profile 回归） |
| AC-7 | A |
