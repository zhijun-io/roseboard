# Roseboard Queue Runtime Advanced Plan

> **Spec：** [`013-queue-runtime-advanced-spec.md`](013-queue-runtime-advanced-spec.md)（已批准）  
> **前置：** 建议在 [`006-cluster-runtime-phase2-plan.md`](006-cluster-runtime-phase2-plan.md) Slice B 完成后启动 Q1/Q2。

## Slice Q1 — RETRY_FAILED

- **Goal：** `ProcessingStrategyExecutor` + `RETRY_FAILED`。
- **Test：** `ProcessingStrategyExecutorRetryTest`（AC-1, AC-2）。
- **Verify：** `mvn test -Dtest=ProcessingStrategyExecutorRetryTest`
- **Status：** Done（交接前已完成）
- **对齐修正：** 按 TB 的 pack 级失败比例分轮重试；成功消息不重复处理；重试间隔按 `pauseBetweenRetries` 递增并受 `maxPauseBetweenRetries` 限制；重试耗尽/比例超限的失败消息统一进入 DLQ。

## Slice Q2 — Dead Letter

- **Goal：** `DeadLetterPublisher` + `{topic}.dlq`。
- **Test：** `QueueFailureDeadLetterTest`（AC-2, AC-6）。
- **Verify：** `mvn test -Dtest=QueueFailureDeadLetterTest`
- **Status：** Done（交接前已完成）

## Slice Q3 — SEQUENTIAL_BY_ORIGINATOR

- **Test：** `SubmitStrategySequentialOriginatorTest`（AC-4）。
- **Status：** Done
- **Result：** `QueueMsgPackPipeline` 按 `QueueMessage.getKey()` 分组；同 key 逐条串行，不同 key 组并行；processing strategy 继续委托现有 executor。
- **Verify：** `mvn -q -Dtest=SubmitStrategySequentialOriginatorTest,QueueMsgPackPipelineTest,ProcessingStrategyExecutorRetryTest,QueueFailureDeadLetterTest test`（通过）

## Slice Q4 — BATCH

- **Test：** `SubmitStrategyBatchTest`（AC-5）。
- **Status：** Done
- **Result：** `PartitionRunner` 将每次 poll 结果作为一个 pack；`QueueMsgPackPipeline` 按 `batchSize` 在 pack 内顺序提交 chunks，与 TB `BatchTbRuleEngineSubmitStrategy` 对齐。
- **Verify：** `mvn -q -Dtest=SubmitStrategyBatchTest,SubmitStrategySequentialOriginatorTest,QueueMsgPackPipelineTest,ProcessingStrategyExecutorRetryTest,QueueFailureDeadLetterTest test`（通过）

## Slice Q5 — Revoke + RETRY 联调

- **Test：** 依赖 006 Slice C（AC-7）。
- **Status：** Done
- **Result：** `CorePartitionRevokeIntegrationTest` 覆盖 retry 中 revoke；revoke 等待第二次成功后 commit，peer 不再重复投递。
- **Verify：** `mvn -q -Dtest=CorePartitionRevokeIntegrationTest test`（通过）

## Close-out verification

- **Verify：** `mvn -q -Dtest=SubmitStrategyBatchTest,SubmitStrategySequentialOriginatorTest,QueueMsgPackPipelineTest,ProcessingStrategyExecutorRetryTest,QueueFailureDeadLetterTest,CorePartitionRevokeIntegrationTest test`（已拆分执行并通过）
- **Note：** 更宽的 `QueueConfigurationIT` 组合运行受既有 WebSocket 测试上下文缺少 `jakarta.websocket.server.ServerContainer` 影响，未将该环境问题归因于 013。
- **本次对齐验证：** 定向 Queue 测试在干净编译前通过；执行 `mvn -q clean -DskipTests compile` 后，更宽测试被工作区已有 `NotificationTargetService.java` 语法错误阻断，未修改该无关文件。
- **本次继续对齐：** `pauseBetweenRetries` / `maxPauseBetweenRetries` 按 TB 解释为秒；BATCH 不再跨 poll 累积。最新回归被工作区已有 Notification 模块语法错误阻断。
- **后续补齐：** `RETRY_ALL`、`RETRY_TIMED_OUT`、`RETRY_FAILED_AND_TIMED_OUT` 使用 pack 级 `packProcessingTimeout` 标记超时结果；`SKIP_ALL_FAILURES_AND_TIMED_OUT` 超时后完成，不再无限等待。定向 Queue 与 revoke 回归通过。
