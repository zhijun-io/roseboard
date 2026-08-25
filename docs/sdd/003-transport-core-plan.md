# Transport Core Implementation Plan

**Spec:** [`003-transport-core-spec.md`](003-transport-core-spec.md)

> **状态：已验收。** Slice 1–3 完成（2026-08-19）。
> **会话编排为 TB 迁移必交付**（AC-2）。先于 HTTP Adapter；可与 [`006`](006-cluster-runtime-spec.md) 并行，但不可裁掉 Session 编排。

## Slice 1: 多凭证鉴权

- **Goal:** 四类凭证校验委托 `DeviceCredentialService`；HTTP 路径经 `TransportService.validateDeviceToken`。
- **Acceptance:** AC-1
- **Depends on:** None
- **Flyway:** 无
- **Test:** `DeviceCredentialsAuthenticationIntegrationTest` — ACCESS_TOKEN / MQTT_BASIC / X509 / LWM2M 各一例成功 + 非法凭证 → `null`
- **Implementation:**
  - 领域：`DeviceCredentialService` 四类 `authenticate*` 方法（禁止复制校验逻辑）
  - Transport：`TransportService` / `DefaultTransportService.validateDeviceToken`（Access Token + `DeviceMapper` 租户校验）
- **Verification:**

```bash
mvn -q -Dtest=DeviceCredentialsAuthenticationIntegrationTest test
```

- **Done:** true

## Slice 2: 内存 Session 编排（TB 迁移）

- **Goal:** `TransportSessionRegistry`：`register` / `recordActivity` / `closeSession` / `deliver` / 超时；`nextRequestId` + `associateRpc`/`resolveRpcId`（内存 Map）。`TransportService.registerSyncSession` 等委托注册表。
- **Acceptance:** AC-2
- **Depends on:** Slice 1
- **Flyway:** 无
- **Test:** `TransportSessionIntegrationTest` — 注册后 `deliver` 唤醒 listener；`recordActivity` 更新时间；超时/`closeSession` 后 `deliver` → `NO_SESSION`/`SESSION_CLOSED`；`nextRequestId` 单调递增
- **Implementation:**
  - `TransportSessionRegistry`（`sessionId` 表 + `deviceId` → 活跃 session 索引）
  - `SessionMsgListener`、`TransportToDevicePayload`、`DeliverResult`、`SessionInfo`
  - 可注入 `Clock` / 测试用可控超时
- **Verification:**

```bash
mvn -q -Dtest=TransportSessionIntegrationTest,DeviceCredentialsAuthenticationIntegrationTest test
```

- **Done:** true

## Slice 3: Queue 上下行 + ContentType

- **Goal:** `publishUplink` → `Main`；`TransportNotifications` consumer → `deliver`；默认 JSON + `telemetry.post` Protobuf 一条路径；无 Core 直写领域。
- **Acceptance:** AC-3, AC-4, AC-5, AC-6
- **Depends on:** Slice 1–2
- **Flyway:** 无（AC-5 门禁：diff 无新表）
- **Test:** `TransportQueueIntegrationTest`
  - 上行：`publishUplink` 后测试 consumer 从 `tb_core.main` 收到 JSON `telemetry.post`
  - Protobuf：同 messageType 以 `application/x-protobuf` 入队并可解码
  - 下行：向 `tb_transport.notifications` 发布 `transport.to-device`，已注册 Session 的 listener 被唤醒；无 Session → `NO_SESSION`
- **Implementation:**
  - `TransportMessageTypes` 常量（queue 名 / topic / 固定 id / consumer group）
  - `DefaultTransportService.publishUplink` → `CodecRegistry` + `TransportMessageTypes.toQueueMessage` + `TransportQueueRuntime.mainProducer()`
  - `TransportQueueRuntime`（`TransportConfiguration`）：`QueueService.findOrCreate` + `QueueCoordinator.registerBinding`（Main：`transport-core-main`；TransportNotifications：`transport-core`）+ `startIfBound`
  - Spring：`TransportConfiguration` 注册 Codec 中 `telemetry.post` + `transport.to-device`（JSON；Protobuf 至少 `telemetry.post`）
  - Main 领域 save 由 `TransportQueueRuntime.handleMain` 交付（004 HTTP 验收）；003 IT 用 `stopIfBound(Main)` + 直接 poll 断言入队
- **Verification:**

```bash
mvn -q -Dtest=DeviceCredentialsAuthenticationIntegrationTest,TransportSessionIntegrationTest,TransportQueueIntegrationTest test
```

- **Done:** true

## 实施钉死项

1. **唯一 Core 入口：** 协议 Adapter 与 `DeviceRpcController` 在线投递 **只** 调 `TransportService` / `DefaultTransportService`，禁止旁路 Session 表或直写领域（上行）。
2. **Queue 名/topic：** 与 Spec 表一致——上行 `Main` / `tb_core.main`；下行 `TransportNotifications` / `tb_transport.notifications`；IT 与生产共用 `TransportMessageTypes`。
3. **Envelope：** `QueueMessage.key` = `deviceId`；metadata 必含 `tenantId`、`deviceId`；编解码 **仅** 经 `CodecRegistry` + `TransportMessageTypes`（`toQueueMessage` / `decode`）。
4. **Queue 绑定：** 无独立 `QueueRuntimeBinding` 类型；`consumerGroup` 在 `QueueCoordinator.registerBinding` 注入，不写入 `queue` 表。
5. **单设备单 SYNC Session：** 新 `registerSyncSession` 替换同 `deviceId` 旧会话（先 `close` 旧 listener），对齐 TB HTTP。
6. **requestId：** 会话内从 1 递增；`associateRpc` 仅 `ConcurrentHashMap`；**禁止** Flyway 映射表。
7. **鉴权：** 四类凭证在 `DeviceCredentialService`；HTTP `validateDeviceToken` 失败返回空 `DeviceInfo`；不得通过异常 message 泄露它租户设备存在性。
8. **Main 消费者：** `TransportQueueRuntime.handleMain` 绑定 `Main`（004 HTTP 验收）；003 IT 用 `stopIfBound` + poll，不必在 IT 里重复领域 save。
9. **包布局：** `com.roseboard.infrastructure.transport`（core/session/queue 子包可建，**禁止** `device.session` 与协议解析）。

## AC → Slice

| AC | Slice |
|----|-------|
| AC-1 | 1 |
| AC-2 | 2 |
| AC-3 | 3 |
| AC-4 | 3 |
| AC-5 | 3 |
| AC-6 | 3 |

## Build log

- 2026-08-19 | Slice 1 多凭证鉴权 — `TransportCore`/`DefaultTransportCore`/`TransportAuthRequest`；`TransportAuthIntegrationTest` 5/5 | Verification **exit 0**
- 2026-08-19 | Slice 2 内存 Session — `TransportSessionRegistry`；`TransportSessionIntegrationTest` 8/8 | Verification **exit 0**
- 2026-08-19 | TB 对齐清理 — 移除 `TransportCore`/`TransportAuthRequest`；IT 基类与用例重命名为 `TransportIntegrationTestBase` / `Transport*IntegrationTest` | Verification **exit 0**
- 2026-08-20 | 文档对齐实现 — Core API 改为 `TransportService`/`TransportSessionRegistry`；AC-1 测试类改为 `DeviceCredentialsAuthenticationIntegrationTest` | none — doc sync | Spec revision log
