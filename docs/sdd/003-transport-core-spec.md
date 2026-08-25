# Roseboard Transport Core Spec

> **状态：已验收。** Slice 1–3 已落地（2026-08-19）。  
> Plan：[`003-transport-core-plan.md`](003-transport-core-plan.md)

## Goal

**迁移并落地** ThingsBoard 传输层 **会话编排**（`TransportService`：同步会话注册、activity、下行投递、超时释放）以及协议无关的鉴权 / Queue 上下行 / ContentType / 整型 requestId——实现为 Roseboard Transport Core。

**不迁移** Device Actor、Rule Engine、TbMsg、`TransportProtos` 全量。供后续 HTTP/MQTT 等 Adapter Spec 挂接；本 Spec **不实现**协议 Adapter。`Main` 领域 save 由 `TransportQueueRuntime.handleMain` 经 Queue consumer 交付（004 HTTP 验收）；003 验收仅要求入队与下行 `deliver`，IT 可暂停 Main binding 后直接 poll 断言。

## Dependencies

- Requires: [`001`](001-non-transport-domain-completion-spec.md)（Credentials、Queue 运行时）；[`002`](002-tenant-profile-usage-spec.md)（Transport 计量钩子，供后续消费者复用）；`DeviceCredentialService`；`com.roseboard.infrastructure.message` Codec（JSON / Protobuf）；`device_rpc`（持久 RPC 映射目标，可选）。
- Provides: `com.roseboard.infrastructure.transport`（Core API + Queue 绑定）。
- Followed by: [`004-transport-http-device-api-spec.md`](004-transport-http-device-api-spec.md)（HTTP Adapter）；[`005-transport-mqtt-device-api-spec.md`](005-transport-mqtt-device-api-spec.md)（MQTT Adapter）；[`010-gateway-subdevices-spec.md`](010-gateway-subdevices-spec.md)（Gateway）。
- Does not own: 具体协议帧解析、HTTP `/api/http`、MQTT/CoAP/LwM2M Adapter、Rule Engine、Queue Provider 内核、Telemetry/Attribute **领域 save 规则**（仅定义入队契约）。

## Scope

- 包：`com.roseboard.infrastructure.transport`（**禁止**放入协议专用解析）。
- 四类凭证：领域 `DeviceCredentialService` 提供校验；Transport SPI 当前封装 HTTP `validateDeviceToken`，其余协议 Adapter 直接委托领域（与 TB 分层一致）。
- 内存 Session：sessionId、SYNC 长轮询 listener、activity、超时释放；**整型 requestId** 与会话内 UUID 关联（**无** SQL 映射表）。
- 上行：Adapter/Core → **`Main` Queue publish**（默认 JSON ContentType）。
- 下行：Domain/管理路径 → **`TransportNotifications` Queue 直发**（无 outbox）→ Core consumer → 内存 Session listener。
- ContentType：`application/json`（默认）、`application/x-protobuf`（既有 `CodecRegistry`）。
- **Adapter 边界**：Adapter 只做「协议字节/请求 ↔ Core 调用」；不得在 Adapter 内分叉实现入队拓扑或领域 save。

## Core API（单节点）

实现分层（对齐 TB `TransportService`，非单一 `TransportCore` 接口）：

| 层 | 类 | 职责 |
|---|---|---|
| Adapter SPI | `TransportService` / `DefaultTransportService` | 凭证校验（HTTP Access Token）、`process*` 入队、Session 注册/activity |
| Session 注册表 | `TransportSessionRegistry`（包内组件） | `register` / `deliver` / `closeSession` / `recordActivity` / `nextRequestId` / `associateRpc` |
| Queue 装配 | `TransportQueueRuntime` | 双队列 binding、Producer、`handleMain` / `handleDownlink` |

**Adapter 对外 SPI** — `DefaultTransportService`（Spring `@Service`）：

```java
public interface TransportService {

    /** HTTP 设备面：Access Token 校验；失败 callback 返回空 DeviceInfo，不泄露跨租户信息。 */
    void validateDeviceToken(String deviceToken,
                             TransportServiceCallback<ValidateDeviceCredentialsResponse> callback);

    /** 遥测/属性上行：recordActivity 后入 Main Queue；禁止同步直调 Telemetry/Attribute save。 */
    void process(SessionInfo sessionInfo, PostTelemetryMsg msg, TransportServiceCallback<Void> callback);
    void process(SessionInfo sessionInfo, PostAttributeMsg msg, TransportServiceCallback<Void> callback);

    /** GET attributes：recordActivity 后直查领域，经 listener 回调响应（不经 Queue）。 */
    void process(SessionInfo sessionInfo, GetAttributeRequestMsg msg, TransportServiceCallback<Void> callback);

    /** TB registerSyncSession 语义：挂 listener，超时或 deregister 后注销。 */
    void registerSyncSession(SessionInfo sessionInfo, SessionMsgListener listener, long timeout);

    void deregisterSession(SessionInfo sessionInfo);

    void recordActivity(SessionInfo sessionInfo);
}
```

**包内协作（IT / Queue consumer 同包调用）：**

- `DefaultTransportService.publishUplink` / `publishDownlink` — 003 Queue IT 与内部入队/下行发送。
- `TransportSessionRegistry.deliver(deviceId, TransportToDevicePayload)` — 下行 Queue consumer 与 Session IT 共用。
- `TransportSessionRegistry.nextRequestId` / `associateRpc` / `resolveRpcId` — RPC 切片预留（004）。

**四类凭证：** 领域层 `DeviceCredentialService` 提供 `ACCESS_TOKEN` / `MQTT_BASIC` / `X509_CERTIFICATE` / `LWM2M_CREDENTIALS` 校验；Transport SPI 当前仅封装 HTTP 所需的 `validateDeviceToken`，其余协议 Adapter 直接委托 `DeviceCredentialService`（与 TB 一致）。

| 类型 | 契约 |
|------|------|
| `SessionInfo` | `sessionId`、`deviceId`、`tenantId`、`deviceProfileId`、activity 时间戳等（Adapter 鉴权后构造） |
| `SessionMsgListener` | `onMessage(TransportToDevicePayload)`；可选 `onGetAttributesResponse` / `onSessionClose` |
| `TransportToDevicePayload` | 下行 payload；持久 RPC 含整型 `requestId` |
| `DeliverResult` | `DELIVERED` \| `NO_SESSION` \| `SESSION_CLOSED` |
| `ValidateDeviceCredentialsResponse` | 鉴权成功含 `DeviceInfo`；失败字段为 null |

**Session 注册表（进程内）：** `TransportSessionRegistry` — `ConcurrentHashMap<UUID, SessionEntry>`（key=`sessionId`）+ `ConcurrentHashMap<UUID, UUID>`（key=`deviceId`→当前活跃 `sessionId`；单设备单 SYNC 会话）。超时用 `ScheduledExecutorService` + 可注入 `Clock`。

## Queue 拓扑与消息契约

对齐 [`CONTEXT.md`](../../CONTEXT.md) Messaging；**无 Rule Engine**，上行 `Main` 取代 TB 的 `tb_rule_engine.main` 作为**领域写入入口**（经 Queue consumer 异步 save，非 Core 内同步直写）。

| 方向 | Queue `name` | `topic` | 生产者 | 消费者 |
|------|--------------|---------|--------|--------|
| 上行 | `Main` | `tb_core.main` | `DefaultTransportService` 入队（`publishUplink` / `process`） | `TransportQueueRuntime.handleMain` 经 `registerBinding`（consumer group `transport-core-main`）；003 IT 可 `stopIfBound` 后直接 poll |
| 下行 | `TransportNotifications` | `tb_transport.notifications` | 管理端 RPC / 004 适配层 / `DefaultTransportService.publishDownlink` | `TransportQueueRuntime.handleDownlink`（consumer group `transport-core`）→ `deliver` |

**Queue 运行时绑定（收敛模型）：**

```text
queue 表 / QueueDefinition
  → toTransportConfig() → QueueTransportConfig（topic、分区、策略）
  → QueueCoordinator.registerBinding(queueName, consumerGroup, handler)
  → QueueConsumerManager 启动（仅已注册 binding 的 queue name）
```

- **配置与绑定分离：** `consumerGroup` 在 `registerBinding` 时注入，**不**写入 `queue` 表或 `QueueDefinition`；每个 queue **name** 仅允许一个 binding（重复注册抛 `IllegalStateException`）。
- **生命周期：** CRUD 经 `QueueService.createManaged` / `updateManaged` / `deleteManaged` 与 binding 联动；无 binding 的 queue 仅持久化、不启动 consumer。
- **装配入口：** `TransportConfiguration` → `TransportQueueRuntime.initialize()`：`QueueService.findOrCreate` + 双队列 `registerBinding` + `startIfBound`。
- **常量：** queue 名 / topic / 固定 id / consumer group 见 `TransportMessageTypes`（003/004 共用，勿再引入 `TransportQueueTopics` 等平行常量类）。

**Envelope：** `CodecRegistry.encode` → `MessageEnvelope` → `TransportMessageTypes.toQueueMessage`。Queue **key** = `deviceId` 字符串（稳定分区，对齐 TB UUID→String key 规则）。

**必填 metadata（string）：**

| Key | 说明 |
|-----|------|
| `tenantId` | UUID |
| `deviceId` | UUID |
| `sessionId` | 可选；调试/关联 |

**本 Spec 钉死的 messageType：**

| messageType | schemaId | schemaVersion | 用途 |
|-------------|----------|---------------|------|
| `telemetry.post` | `telemetry.v1` | 1 | AC-3/4 默认 JSON + Protobuf 路径（与现有 Codec 测试一致） |
| `transport.to-device` | `transport.v1` | 1 | AC-6 下行投递 IT；004 RPC/attributes 扩展同族 |

未指定 `contentType` 时走 `CodecRegistry.defaultContentType(messageType)`；未知类型按既有 `MessageContractException` 失败。

**003 集成测试：** 生产路径由 `TransportQueueRuntime` seed `queue` 表并 `registerBinding`。上行 IT 典型做法：`runtimeService.stopIfBound("Main")` 暂停领域 consumer，经 `InMemoryQueueStorage` + 临时 `InMemoryQueueConsumer` poll 断言；下行 IT 经 producer 入队后验证 Session listener。

## Session orchestration（TB 迁移，无 Actor）

本 Spec **必须迁移** TB 会话编排语义；实现形态为内存 Session + Queue，**不是**可选设计说明。

| TB `TransportService` | Roseboard（须实现） |
|----------------------|---------------------|
| SessionInfo | `SessionInfo` record |
| `registerSyncSession(..., listener, timeout)` | `TransportService.registerSyncSession` → `TransportSessionRegistry.register` |
| `recordActivity` | `TransportService.recordActivity` |
| 下行 → SessionMsgListener | Queue consumer → `TransportSessionRegistry.deliver` → listener |
| 会话关闭 / 超时 | `deregisterSession` / 超时 → `SESSION_CLOSED`；SYNC 投递后单次关闭 |

| 职责 | 实现 |
|------|------|
| 每设备在线上下文 | 内存 Session 注册表 + deviceId 索引 |
| 等待下行 | SYNC listener + 超时 |
| 投递 | `deliver` 查表唤醒（单节点） |
| 上行 | `DefaultTransportService.process` / 包内 `publishUplink` → `Main` Queue |
| requestId | 会话内递增；`associateRpc` 仅内存 |

**明确不迁**：Actor 邮箱、邮箱持久化、TbMsg、规则链。  
多节点 Session 路由 → **[`006`](006-cluster-runtime-spec.md)**（本 Spec 先保证单节点与 TB 语义一致）。

## requestId 与 device_rpc（无映射表）

1. `nextRequestId(sessionId)` 在会话内从 **1** 单调递增（TB 设备线整型 id）。
2. 持久 RPC：创建 `device_rpc` 行后，调用 `associateRpc(sessionId, requestId, rpcUuid)`；映射仅存 **Session 存活期** 的进程内 Map `(sessionId, requestId) → rpcUuid`。
3. 会话关闭或超时后映射丢弃；设备再次 `GET .../rpc` 时由 004 从 `device_rpc` 队列状态重新分配整型 id 并关联。**禁止** Flyway 映射表、`device_transport_session`、或 `com.roseboard.device.session` 包。

## Current Context

- [`CONTEXT.md`](../../CONTEXT.md)：产品决策；Messaging；无 Actor 编排说明。
- 既有：`DeviceCredentialService` 四类鉴权、`queue.*` 运行时、`infrastructure.message` Codec、`device_rpc`；`DeviceRpcController` 非持久 RPC 暂 504。

## TB Alignment（Core）

| 面 | TB | Roseboard Core |
|----|----|----------------|
| 上行 | Transport → Queue → 服务 | **相同**（`Main` 取代 RE 入口） |
| 下行 | 服务 → Queue → Transport | **相同**（`TransportNotifications`） |
| Session 编排 | `registerSyncSession` / `recordActivity` / listener 投递 | **须迁移同语义**（无 Actor） |
| 凭证类型 | 四类 | **相同**（`DeviceCredentialService`；HTTP 经 `validateDeviceToken`） |
| 载荷 | JSON / Protobuf | **相同**；**默认 JSON** |
| 设备 RPC 线 id | 整型 requestId | **`TransportSessionRegistry` 分配/内存关联 UUID** |

## Requirements

1. 四类凭证由 `DeviceCredentialService` 校验（AC-1）；`TransportService.validateDeviceToken` 封装 HTTP Access Token 路径；失败无 DeviceInfo、不泄露跨租户信息。
2. Core **迁移** TB 会话编排：`TransportSessionRegistry` 负责注册/释放/activity；SYNC listener；超时释放；`deliver` 可唤醒 listener。
3. 上行业务消息经 **`DefaultTransportService.process` 或包内 `publishUplink` 入 `Main` 队列**；禁止在 Transport SPI 内同步直调 Telemetry/Attribute save。
4. 下行消息经 **`TransportNotifications` 队列** 到达 Core consumer 后 `deliver` 到已注册 Session；无等待会话时返回 `NO_SESSION`（004 映射为 504 等）。
5. 未指定 ContentType 时默认 JSON；支持 Protobuf ContentType + 已注册 Codec（`telemetry.post`）。
6. 会话内 `nextRequestId` + 可选 `associateRpc`；不新建 SQL 映射表。
7. 无 inbox/outbox；不使用 SQL Session 表；无 Actor / RE；无新 Flyway 业务表。
8. 集成测试覆盖鉴权、Session、上行入队、下行 Queue→deliver、默认 JSON、至少一条 Protobuf（可无 HTTP）。

## Acceptance Criteria

- AC-1: When 分别向 `DeviceCredentialService` 提交合法四类凭证材料, then 得到 `DevicePrincipal`；When 非法, then `null`（`DeviceCredentialsAuthenticationIntegrationTest`）。HTTP 路径 `TransportService.validateDeviceToken` 成功返回 `DeviceInfo`、失败返回空响应。
- AC-2: When `TransportSessionRegistry.register` 挂 listener, then 超时前 `deliver(deviceId, …)` 唤醒 listener；When `recordActivity` 后, then `lastActivityAt` 更新；When 超时或 `closeSession`, then 再 `deliver` 返回 `NO_SESSION`/`SESSION_CLOSED` 且不重复回调 listener。
- AC-3: When `publishUplink` 提交 `telemetry.post`, then 消息进入 `Main` 并被测试 consumer 收到（默认 JSON ContentType）；路径不得仅为 Core 内直接领域 save。
- AC-4: When 未指定 ContentType, then 按 `application/json`；When 使用 Protobuf ContentType 且 `telemetry.post` 已注册, then 入队 payload 可经 `CodecRegistry` + `TransportMessageTypes.decode` 解码为等价领域值。
- AC-5: When 实现本 Spec, then 无新业务 Flyway 表、无 requestId 映射表、无 inbox/outbox、无新 `device.*` Entity、无新凭证类型；仓库中无 `device_transport_session` 表与 `com.roseboard.device.session` 包；无 Actor/RE 依赖。
- AC-6: When 向 `TransportNotifications` 发布 `transport.to-device` 且设备已有 SYNC Session, then listener 收到等价下行；When 无 Session, then `deliver` 结果为 `NO_SESSION`（不泄露其它租户信息）。

## Constraints

- 包仅 `com.roseboard.infrastructure.transport`；编解码复用 `com.roseboard.infrastructure.message.CodecRegistry` + `TransportMessageTypes` 队列边界转换。
- Session 仅内存；Queue 直发下行；默认 JSON；无 Actor。
- 验收以 IT 为准（`@SpringBootTest` + Testcontainers；可无 MockMvc HTTP）。
- **会话编排为 TB 迁移交付物**，不可裁剪为「仅文档」；协议 Adapter（004 等）可稍后。

## Non-goals

- HTTP / MQTT / CoAP / LwM2M / SNMP **Adapter**（**004** 及后续）——**不含**本 Spec 已交付的 Session / Queue 内核。
- `Main` 队列 Telemetry/Attribute **领域 save 规则**（由 `TransportQueueRuntime.handleMain` 实现；003 验收不要求 IT 覆盖 save）。
- Rule Engine、Actor、TbMsg。
- Alarm / Asset / Event / Edge / EntityView / Dashboard（产品暂缓）。
- 遥测/属性 Redis 缓存与版本一致性（**[`007`](007-cache-consistency-spec.md)**）。
- inbox/outbox；SQL transport session；requestId 映射表；新凭证类型；新领域 Entity。
- TB `TransportProtos` 全量运行时。

## Decisions

- Chosen: **迁移** TB `TransportService` 会话编排语义；实现 = 内存 Session + `Main`/`TransportNotifications` Queue；**拒绝 Actor / RE**。
- Chosen: Core **协议无关**；JSON+Protobuf（默认 JSON）；整型 requestId + 会话内内存 UUID 关联。
- Chosen: 单设备单活跃 SYNC Session（对齐 TB HTTP）；Gateway 多会话 **[`010`](010-gateway-subdevices-spec.md)**。
- Chosen: Queue 名/topic 上表固定，避免 003/004 各写一套 envelope。
- Rejected: 跳过会话编排只写文档；SQL Session 作真相；inbox/outbox；TransportProtos 全量；用 Actor「补齐」；Core 内直写领域作为主路径。

## Open Questions

（无 — 集群多节点 Session 路由细节归 [`006`](006-cluster-runtime-spec.md)）

## Revision log

- 2026-08-19 | 从合并 Spec **拆出** Transport Core | AC-1..5 | 原合并文档
- 2026-08-19 | 删除遗留 SQL Session 包/表与占位 IT | AC-5 措辞 | none — 仓库已清理
- 2026-08-19 | 文件改名为 `001-transport-core-spec.md` | none — clarification | plan: 后随交付顺序重编号
- 2026-08-19 | 明确无 Actor 会话编排；产品暂缓 Alarm/Asset/Edge；缓存另 Spec；集群要对齐；Adapter 稍后 | Scope/Non-goals/Decisions | plan impact: yes
- 2026-08-19 | **Core 会话编排列为 TB 必迁交付**（非可选）；强化 AC-2 / Goal | AC-2；Decisions | plan impact: yes — Plan 须交付会话编排 IT
- 2026-08-19 | 按交付顺序重编号为 `003-transport-core-spec.md` | none — clarification | plan: `003-transport-core-plan.md`
- 2026-08-19 | 全库 Spec 再排序：001 非传输 → 002 Profile/Usage → 003 Core → 004 HTTP → 005 MQTT → 006 集群 → 007 缓存 → 008 Notification → 009 Alarm → 010 Gateway → 011/012 WebSocket → 013 Queue Runtime | none — clarification | CONTEXT
- 2026-08-19 | **完善可 Build 契约**：Core API、Queue 拓扑/messageType、requestId 内存映射、AC-6 下行 Queue、修正 message 包名、明确 Main 消费者归属 004 | AC-6；Requirements 8 | plan: 钉死项 + Slice 细化
- 2026-08-19 | **批准** | none — authorization | CONTEXT；Plan 可 Build
- 2026-08-19 | **Slice 1–3 验收** — Auth/Session/Queue IT 17/17 | AC-1..6 | CONTEXT 已验收
- 2026-08-20 | Queue 运行时文档对齐：`QueueRuntimeBinding` 并入 `QueueCoordinator.registerBinding`；`QueueDefinition`→`QueueTransportConfig` 收敛；`TransportMessageTypes` 取代 `QueueMessageCodecBridge` | Queue 拓扑 / Constraints | plan: Slice 3 实施项同步
- 2026-08-20 | Core API 对齐实现：`TransportService` + `TransportSessionRegistry` 取代 `TransportCore`；AC-1 指向 `DeviceCredentialsAuthenticationIntegrationTest` | Core API / AC-1..2 | plan: Slice 1–2 实施项同步
