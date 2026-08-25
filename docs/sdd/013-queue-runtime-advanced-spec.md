# Roseboard Queue Runtime Advanced Spec

> **状态：已批准**  
> 编号：`013`  
> 依赖：Queue 运行时（`QueueConsumerManager`、`QueueMsgPackPipeline`、`QueueCoordinator`）；[`006-cluster-runtime-phase2-spec.md`](006-cluster-runtime-phase2-spec.md) Slice C（Q5 联调 revoke + retry）。

## Goal

对齐 ThingsBoard Queue **高阶运行时语义**（ProcessingStrategy 重试与 Dead Letter、SubmitStrategy 顺序与批处理），在 **无 Rule Engine** 前提下，使 DB 中已持久化的 `submitStrategy` / `processingStrategy` JSON **可在运行时生效**，而非启动时 reject。

## Scope

### Q1 — ProcessingStrategyExecutor + RETRY_FAILED

- 引入 `ProcessingStrategyExecutor`（或等价），由 `QueueMsgPackPipeline` 委托；替代当前「一律 SKIP」逻辑。
- 支持 **`RETRY_FAILED`**：`retries`、`pauseBetweenRetries`、`maxPauseBetweenRetries` 字段按 TB 语义生效。
- 单条消息失败：重试耗尽后 **不再阻塞 pack**；成功消息仍参与 prefix commit。
- 保持 **`SKIP_ALL_FAILURES`** / **`SKIP_ALL_FAILURES_AND_TIMED_OUT`** 行为与现网一致（回归基线）。

### Q2 — Dead Letter

- `DeadLetterPublisher`：重试耗尽或配置为 skip 且策略要求 DLQ 时，将 **原消息 + 失败元数据** 写入 DLQ。
- DLQ topic 命名：`{queueTopic}.dlq`（或通过 Queue `additional_info` 可配；默认前者）。
- `QueueAdmin` / `KafkaQueueAdmin`：consumer 绑定时 **createTopicIfNotExists** DLQ（Memory adapter 内存 map 等价）。
- DLQ 消息 **不被** 原 consumer 再次 poll。

### Q3 — SEQUENTIAL_BY_ORIGINATOR

- 支持 **`SubmitStrategyType.SEQUENTIAL_BY_ORIGINATOR`**：同一 pack 内，按 `QueueMessage.getKey()`（deviceId 字符串）**串行** submit；不同 key 可并行（与 TB BURST 并发度一致）。
- 与 Main 上行路径对齐：key = deviceId。

### Q4 — BATCH SubmitStrategy

- 支持 **`SubmitStrategyType.BATCH`**：`batchSize` ≥ 1；poll 结果累积至 batchSize 或超时再 submit（超时边界在 Plan 定义，默认对齐 TB poll 周期）。

### Q5 — 与集群 revoke 联调（依赖 006-P2-C）

- 分区 revoke 时，**RETRY 进行中**的消息：revoke drain 等待重试完成或达到 pack timeout；不 commit 未成功前缀。
- 集成测试：2 节点 Memory + `RETRY_FAILED` + 模拟 handler 第 2 次成功。

## Non-goals

- **`duplicateMsgToAllPartitions`**（无 RE）。
- **`QueueRequestTemplate` / `QueueResponseTemplate`**（monolith 无调用方；后续单独立项若出现再开 Spec）。
- SubmitStrategy **`SEQUENTIAL_BY_TENANT`** / **`SEQUENTIAL`**（全局串行）——Phase 2 可选，本 Spec 不验收。
- ProcessingStrategy **`RETRY_ALL`** 等与 pack 级超时交织的复杂组合——本 Spec 仅验收 **RETRY_FAILED** 与 SKIP 基线；其余枚举在 Plan 标注 `unsupported` 直至实现。
- Database Queue Adapter。

## Current Context

- `ProcessingStrategyType` / `SubmitStrategyType` 枚举与 TB 一致；`ProcessingStrategy` record 含 `retries`、`pauseBetweenRetries` 等。
- `QueueMsgPackPipeline.requireSupportedConfig` 当前 **拒绝** 非 BURST / 非 SKIP。
- `QueueEntity` / `QueueService` 已持久化策略 JSON；Transport Main 绑定使用硬编码 BURST + SKIP。
- `QueueConsumerManager` + `PartitionRunner` 已实现 poll/commit；Kafka rebalance 测试存在于 `KafkaFailureAndRebalanceTest`。

## Requirements

1. Queue 启动时，DB 配置 `RETRY_FAILED` + `BATCH` 等 **不再** 抛 `IllegalArgumentException`（在 Spec 声明支持的子集内）。
2. 失败消息在重试耗尽后进入 DLQ，且原分区 offset 可按 TB 规则 commit（不阻塞整 pack 无限期）。
3. `SEQUENTIAL_BY_ORIGINATOR` 保证同 device key 的处理顺序可测。
4. 默认 Transport Main 绑定可继续用 SKIP（或改为可配置），**单节点默认行为不变**。
5. Memory 与 Kafka adapter 对 DLQ 行为一致（Kafka 写真实 topic；Memory 写隔离 store）。

## Acceptance Criteria

- **AC-1**：When Queue `processingStrategy.type=RETRY_FAILED` 且 `retries=2`，handler 前两次失败、第三次成功，then 消息最终被标记成功且 **不** 进入 DLQ。
- **AC-2**：When `RETRY_FAILED` 且 `retries=2`，handler 始终失败，then 消息出现在 `{topic}.dlq`；原 consumer 不再重复投递该条（Memory IT）。
- **AC-3**：When `processingStrategy.type=SKIP_ALL_FAILURES`，then 行为与当前生产路径一致（现有 `QueueMsgPackPipelineTest` / Transport IT 通过）。
- **AC-4**：When `submitStrategy.type=SEQUENTIAL_BY_ORIGINATOR`，同一 pack 内两消息同 key，then handler 调用顺序与 pack 内顺序一致（单元测试 + 可控 handler）。
- **AC-5**：When `submitStrategy.type=BATCH` 且 `batchSize=3`，then 累积 3 条后触发一次 pack submit（Memory IT）。
- **AC-6**：When Kafka Queue 启用 DLQ，then `KafkaQueueAdmin` 创建 `{topic}.dlq` topic；producer 可写入（contract test）。
- **AC-7**：When 006 Phase 2 revoke drain 与 RETRY 并发，then 无 double-process（Q5 IT；可与 006 Plan 合并验收）。

## Constraints

- 不得改变 `QueueMessage` **String key** 契约。
- DLQ payload：至少保留原 headers + data + 失败原因 + 原 topic/partition（JSON envelope，Plan 定 schema）。
- 重试 **同步 sleep 或 scheduler** 须在 worker 线程池内，不得阻塞 consumer poll 线程（与 TB 一致）。
- 未实现的 ProcessingStrategy 枚举：启动 consumer 时 **fail fast** 并给出明确错误（不得 silent fallback 到 SKIP）。

## Decisions

- **Chosen**：先 **RETRY_FAILED + DLQ + SEQUENTIAL_BY_ORIGINATOR + BATCH**；与用户对齐的优先级 P0–P2。
- **Chosen**：DLQ 默认 topic 后缀 `.dlq`；与 TB 常见命名一致。
- **Rejected**：Req-Resp 模板——monolith 无需求。
- **Rejected**：全量一次实现 6 种 ProcessingStrategy——分阶段，降低 rebalance 风险。

## Related ADRs

- （无）

## Open Questions

（无）

## Revision log

- 2026-08-20 | 初稿 | AC-1..AC-7 | plan 待编写；实施顺序在 006-P2 之后或 Q1/Q2 与 006-P2-B 并行
