# Roseboard Cluster Runtime Phase 2 Spec（Core 分区状态机）

> **状态：已批准**  
> 编号：`006` Phase 2（续 [`006-cluster-runtime-spec.md`](006-cluster-runtime-spec.md) Phase 1）  
> 依赖：Phase 1 已交付（`PartitionChangeEvent`、`TransportPartitionManager`、Tenant 隔离、Session Redis 路由）；[`003`](003-transport-core-spec.md)；[`007`](007-cache-consistency-spec.md)（Slice C 钩子，可选后续接）。

## Goal

在 **monolith** 前提下，将 TB **TB_CORE** 分区状态机语义落地为：统一节点发现、`PartitionChangeEvent(TB_CORE)` 驱动 **全部 Queue binding** 的 consumer 热更新，以及分区 revoke 时的 **drain + 连续前缀 commit**；**不**引入 Device Actor / `initStateFromDB`。

## Scope

### Slice A — 统一节点发现

- 引入 `ClusterNodeDiscovery`（或等价命名），ZK/Redis/Memory 三种实现；**Transport 与 Core 共用同一 sorted node 列表**。
- 配置：`roseboard.cluster.*` 为 canonical；`roseboard.transport.cluster.*` 保留为 **deprecated 别名**（读时 fallback，一个版本周期内兼容）。
- ZK 路径：`{rootDir}/nodes/{nodeId}` ephemeral（替代仅 transport 子树）；迁移指南写入 Plan。
- `ClusterServiceType`：`TB_TRANSPORT`（notification 订阅、Session 路由）与 **`TB_CORE`**（Queue consumer 分区）。

### Slice B — Core 分区协调

- `CorePartitionManager`：与 Phase 1 相同规则（`murmur3_128` + `partition % N` + `isolatedTbCore` tenant 分区）；产出本节点应消费的 **Main**（及 Phase 1 已定义之 isolated）`TopicPartitionInfo` 集合。
- 拓扑变化 / 心跳：`scheduleRefresh()` + `recalculate-delay-ms`；发布 **`PartitionChangeEvent(TB_CORE, partitions, reason)`**。
- `QueuePartitionChangeListener`（或等价）：监听 `TB_CORE` 事件，对 **所有已 register 的 Queue binding** 调用 `QueueConsumerManager.update(partitions)`（不仅 Main；TransportNotifications 等 **per-node topic 队列除外**）。
- 移除或收窄 Phase 1 的 `TransportMainPartitionListener` 硬编码 Main 路径，避免双份更新。

### Slice C — 分区 revoke 生命周期

- `PartitionLifecycleListener` 接口：`onPartitionsAdded` / `onPartitionsRemoved`（载荷：`Set<TopicPartitionInfo>` + `ClusterServiceType`）。
- **Removed**：停止对应 `PartitionRunner` poll → 等待当前 pack **in-flight 完成**（受 `packProcessingTimeout` 约束）→ **连续前缀 commit** → unsubscribe。
- **Added**：subscribe 新分区；**不**批量 preload DB（无 Actor）；可选注册点供 [`007`](007-cache-consistency-spec.md) lazy evict/warm。
- 集成测试：Memory 2 节点，模拟节点下线后：其原分区消息由存活节点接管消费；revoke 节点不 double-commit 未完成的 offset。

## Non-goals

- Rule Engine / `isolatedTbRuleEngine` / `duplicateMsgToAllPartitions`。
- Device Actor、`initStateFromDB`、TbMsg。
- Transport / Core **进程拆分**、gRPC。
- WebSocket 集群（011/012）。
- Queue **ProcessingStrategy RETRY/DLQ**（见 [`013-queue-runtime-advanced-spec.md`](013-queue-runtime-advanced-spec.md)）；本 Phase 仅保证 revoke 与 SKIP 策略下 commit 正确；与 013 Q5 联调为 Plan 事项。

## Current Context

- Phase 1：`TransportPartitionManager` 发布 `PartitionChangeEvent(TB_TRANSPORT)`；`TransportMainPartitionListener` 仅更新 Main。
- `QueueCoordinator.registerBinding` + `QueueConsumerManager.update` 已存在；Kafka/Memory consumer 支持分区热更新。
- Monolith：同一 JVM 内既 publish Main 又 consume Main；Core 分区 = 本节点应消费的 Main 分区集合。
- `QueueConsumerManager.PartitionRunner` 已有 poll/commit 循环；prefix commit 行为来自 TB 移植，需与 revoke drain 联验。

## Requirements

1. 集群启用时，Transport 与 Core 使用 **同一** node 列表与 `partition % N` 规则。
2. `TB_CORE` 分区变化时，**所有受 hash 分区约束的 Queue** consumer 订阅集合同步更新。
3. 分区被 revoke 时，不在未 commit 的情况下丢失已处理消息，且不重复消费已 commit 前缀外的消息（在 Memory/Kafka 契约内可测）。
4. `cluster.enabled=false` 时行为与 Phase 1 单节点一致；无新增 mandatory 中间件。
5. Per-node 队列（如 `tb_transport.notifications.{nodeId}`）**不**参与 hash 分区协调。

## Acceptance Criteria

- **AC-1**：When 两节点均启用 `roseboard.cluster.enabled=true` 且 `discovery-type=zookeeper`，then `ClusterNodeDiscovery.sortedNodes()` 与 Phase 1 `sortedTransportNodes()` 返回相同字典序列表。
- **AC-2**：When 拓扑从 3 节点变为 2 节点，then 存活节点收到 `PartitionChangeEvent(TB_CORE)`，且 `CorePartitionManager.currentPartitions()` 按 `partition % 2` 更新。
- **AC-3**：When `PartitionChangeEvent(TB_CORE)` 触发且 Main 分区集合变化，then 已绑定 Main 的 `QueueConsumerManager` 的 `update(partitions)` 被调用一次（无重复订阅泄漏）。
- **AC-4**：When 某 Queue 配置了 hash 分区且非 per-node topic，then 该 Queue 的 consumer 分区集与 Main 使用 **相同** partition index 集合（同一 `CorePartitionManager` 输出，按 queue topic 映射）。
- **AC-5**：When 节点被 revoke 某 Main 分区且该分区上有一条已处理但未 commit 的消息，then revoke 流程等待 pack 完成后 commit，且消息不被另一节点重复消费（Memory 2 节点 IT）。
- **AC-6**：When `cluster.enabled=false`，then 不发布 `TB_CORE` 事件；现有单节点 Queue/Transport 测试无需修改即可通过。
- **AC-7**：When 配置仅保留 `roseboard.transport.cluster.*`（无 `roseboard.cluster.*`），then 应用行为与 canonical 配置等价（别名 fallback）。

## Constraints

- Monolith：所有 listener 为 Spring `@EventListener` 或等价，同 JVM。
- TB 分区规则不变：`HashPartitionService`、`TopicPartitionInfo` 语义与 Phase 1 一致。
- Revoke drain 超时：超过 `packProcessingTimeout` 时 **log + 强制推进** 策略须在 Plan 中写死（对齐 TB 或显式偏差）。
- 不得引入未绑定 Queue 的通用分区编排层。

## Decisions

- **Chosen**：`TB_CORE` 与 `TB_TRANSPORT` **事件分离**——Transport 侧 notification 订阅、Session 路由仍走 `TB_TRANSPORT`；Core consumer 走 `TB_CORE`。
- **Chosen**：Core「状态」= consumer 订阅 + 可选 `PartitionLifecycleListener`；**不**模拟 Actor 邮箱。
- **Rejected**：按 Queue 独立 ZK 发现树——节点列表唯一，分区 index 共享。
- **Rejected**：revoke 时 kill consumer 不等待——与 TB rebalance 语义不符。

## Related ADRs

- （无）

## Open Questions

（无）

## Revision log

- 2026-08-20 | 初稿：Core 分区状态机 Phase 2 | AC-1..AC-7 | plan 待编写
