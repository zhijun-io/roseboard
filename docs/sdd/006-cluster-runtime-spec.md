# Roseboard Cluster Runtime Spec

> **状态：Phase 1 已批准 / 已实施；Phase 2 见 [`006-cluster-runtime-phase2-spec.md`](006-cluster-runtime-phase2-spec.md)**  
> 编号：`006`  
> 依赖：[`003`](003-transport-core-spec.md) Transport Core、Queue 运行时（`TopicPartitionInfo` / `HashPartitionService`）、[`002`](002-tenant-profile-usage-spec.md) Tenant Profile。

## Goal

在 **单进程 monolith**（不拆分 Transport / Core 微服务）前提下，对齐 ThingsBoard 集群运行时的三项能力：ZooKeeper 服务发现与 `PartitionChangeEvent` 全链路、Tenant Profile 驱动的 Queue 分区隔离、Redis 设备 Session 路由元数据（下行寻址与节点故障后 TTL 过期），使多 Transport 节点下 Queue 消费归属与下行投递行为可预期。

## Scope

1. **服务发现（Discovery）**
   - 新增 `roseboard.cluster.discovery.type`：`memory`（单节点默认）、`redis`（现有 ZSET）、`zookeeper`（TB 对齐）。
   - ZK：Curator  ephemeral 节点注册、`sortedTransportNodes()` 与 TB 一致按 nodeId 字典序。
   - 心跳 / 注销 / 重连：`@PostConstruct register`、`@PreDestroy deregister`、连接状态监听。

2. **PartitionChangeEvent 全链路**
   - Spring `PartitionChangeEvent`（或等价应用事件），载荷含 `serviceType=TB_TRANSPORT`、`Set<TopicPartitionInfo> partitions`、触发原因（topology / manual）。
   - 发布方：`ClusterPartitionService`（由 discovery 拓扑变化或 `recalculate_delay` 防抖后触发）。
   - 订阅方：`TransportPartitionManager`、Main Queue `QueueConsumerManager.update(...)`（经 `TransportConfiguration` 桥接）。
   - 替换当前仅 `Runnable` listener 的隐式刷新；保留 `assigned-partitions` 手工 override。

3. **Tenant 分区隔离**
   - 读取 Tenant Profile `isolatedTbCore`（`false` 默认，与 TB 一致）。
   - `isolatedTbCore=true` 时：上行 `HashPartitionService.resolve(..., tenantId, ...)` 构造带 `tenantId` 的 `TopicPartitionInfo`（`fullTopicName` 追加 `.isolated.{tenantId}`）。
   - Consumer 侧：本节点除共享 Main 分区外，还订阅该节点负责 partition index 上的 **isolated tenant** 分区集合（按拓扑 `% servers.size()` 同一规则）。
   - `isolatedTbRuleEngine` **不实现**（无 RE）。

4. **Session Redis 恢复（路由元数据，非 listener 恢复）**
   - 设备 Session **listener 仍仅进程内内存**（与 [`003`](003-transport-core-spec.md) 一致）。
   - Redis 持久化 **路由记录**：`deviceId → {nodeId, sessionId, tenantId, protocol, lastActivityAt}`，TTL = session inactivity + grace。
   - `register` / `close` / `recordActivity` 同步 Redis；`publishDownlink` 优先本地 session，否则读 Redis 取 `nodeId` 发 notification topic。
   - 节点启动：清理本 `nodeId` 下已过期或孤儿键（best-effort）；**不**尝试恢复 listener / 长轮询状态。
   - `cluster.enabled=false` 时使用 `InMemoryTransportSessionStore` no-op。

## Non-goals

- Transport / Core **微服务拆分**、gRPC、`TransportProtos` 内部化。
- Rule Engine 分区、`isolatedTbRuleEngine`、Tenant 级独立 Kafka 集群。
- SQL `device_transport_session` 表复活（V56 已删）；Session 全量 Redis 镜像。
- WebSocket 集群（已由 `011`/`012` 覆盖，本 Spec 不重复）。
- Protobuf 载荷、Actor 邮箱、TbMsg。

## Current Context

- 已有：`TransportClusterDiscovery`（InMemory / Redis ZSET）、`TransportPartitionManager`（`partition % N`）、per-node `tb_transport.notifications.{nodeId}` 下行。
- 已有：`TopicPartitionInfo.tenantId` 字段与 `.isolated.{tenantId}` 命名规则；`TenantProfileEntity.isolatedTbCore` 列与默认值 `false`。
- 已有：`TransportSessionRegistry` 纯内存；`SessionInfo.nodeId`；`activeNodeId(deviceId)` 供下行路由。
- 已有：`QueueConsumerManager.update(Set<TopicPartitionInfo>)` 支持分区热更新。
- 依赖：`spring-boot-starter-data-redis` 已引入；**无** Curator/ZK 依赖（本 Spec 新增可选依赖）。

## Requirements

1. 集群启用时，Transport 节点可通过 ZK（或 Redis fallback）被发现，列表与 TB 相同按 nodeId 排序。
2. 拓扑变化（节点上下线）在 configurable delay 后触发分区重算，并通过 **统一事件** 通知所有订阅者更新 Kafka/Memory consumer 订阅。
3. 默认租户（`isolatedTbCore=false`）行为与当前单节点一致；隔离租户的上行入队与 consumer 订阅使用 isolated `TopicPartitionInfo`。
4. 多节点下行：本节点无 session 时，可从 Redis 读取设备所在 `nodeId` 并投递到对应 notification partition。
5. 单节点 / `cluster.enabled=false` 时，不强制 ZK/Redis Session；零配置行为不变。
6. 所有新行为可通过 `application.yml` 关闭，且不破坏现有集成测试（默认 profile 下）。

## Acceptance Criteria

- **AC-1**：When `roseboard.transport.cluster.discovery-type=zookeeper` 且 ZK 可用，then 两个不同 `node-id` 的实例均出现在 `sortedTransportNodes()`，且顺序为字典序。
- **AC-2**：When ZK 上某 Transport 节点 ephemeral 节点消失，then 在 `recalculate-delay-ms` 之后剩余节点收到 `PartitionChangeEvent`，且 `TransportPartitionManager.currentMainPartitions()` 按 `partition % servers.size()` 更新。
- **AC-3**：When `PartitionChangeEvent` 触发且 Main 分区集合变化，then `QueueConsumerManager` 对该 Queue 的 `update(partitions)` 被调用，集成测试可断言 consumer 订阅集合变化（Memory adapter）。
- **AC-4**：When 租户 Profile `isolatedTbCore=true` 且设备上行 telemetry，then 入队 `TopicPartitionInfo.fullTopicName` 包含 `.isolated.{tenantId}`；When `false`，then 不包含 isolated 后缀。
- **AC-5**：When 隔离租户设备上行且集群 2 节点，then 仅负责该 partition index 的节点 consumer 能 poll 到消息（Memory 集成测试）。
- **AC-6**：When 设备在节点 A 注册 async session 且 Redis session store 启用，then Redis 存在 `deviceId→nodeA` 记录；When 节点 B 调用 `publishDownlink`，then 消息进入 `tb_transport.notifications.nodeA` 而非 nodeB。
- **AC-7**：When session 关闭或 TTL 过期，then Redis 路由键删除；When 无本地 session 且无 Redis 记录，then 下行不 publish（与当前「无 session 即 skip」一致）。
- **AC-8**：When `cluster.enabled=false`，then 不连接 ZK、不写 Redis session；现有 `TransportSessionIntegrationTest` / `TransportQueueIntegrationTest` 无需额外中间件即可通过。

## Constraints

- **Monolith**：Discovery / Partition / Session store 均为 Spring Bean，同 JVM 内事件驱动；不引入独立 Transport 进程。
- **TB 分区规则**：Hash `murmur3_128`；归属 `partition % servers.size()`；`useInternalPartition` 由 `consumer-per-partition` 配置决定（与现有一致）。
- **ZK 可选**：`discovery-type=zookeeper` 时引入 `curator-recipes`；未启用时不拉 ZK 连接。
- **Redis Session 可选**：`roseboard.transport.cluster.session-store=redis|memory`；默认 `memory`。
- **防抖**：`recalculate-delay-ms`（默认 0 transport，可配 3000ms 对齐 TB）避免重启风暴。
- **安全**：Redis session 键不含 credential；仅路由元数据。

## Decisions

- **Chosen**：ZK 为 TB 对齐_discovery 首选_；Redis ZSET 保留为无 ZK 环境的 fallback（云原生简化部署）。
- **Chosen**：`PartitionChangeEvent` 采用 Spring `ApplicationEvent`，与 TB 语义对齐且便于单体内多 listener 扩展（Usage、Telemetry cleanup 等后续可订阅）。
- **Chosen**：Session Redis **仅路由**，不恢复 listener；设备重连 = 新 session，旧 Redis 键 TTL 过期。对齐 TB「session 在 Redis 做跨节点查找，连接状态 ephemeral」模型。
- **Rejected**：恢复 SQL session 表 — 与 003/CONTEXT「Session 仅内存」冲突，且 TB 新版亦倾向 cache。
- **Rejected**：按租户独立 ZK 树 — 过度设计；isolation 仅体现在 `TopicPartitionInfo.tenantId`。

## Related ADRs

- （无）首版；若 ZK vs Redis 长期策略需跨 Spec 引用，可补 ADR。

## Open Questions

（无 — 用户已确认：对齐 ZK + PartitionChangeEvent + Tenant 隔离 + Session Redis；不做微服务拆分。）

## Revision log

- 2026-08-20 | Phase 2 拆至独立 Spec | none | plan: 006-phase2-plan 待编写
- 2026-08-20 | 用户确认批准 | AC-1..AC-8 | plan: 按四切片实施
- 2026-08-20 | 自 stub 扩展为可实施 Spec | AC-1..AC-8 新增 | plan 待编写
