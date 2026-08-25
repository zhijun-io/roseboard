# Transport MQTT Device API Implementation Plan

**Spec:** [`005-transport-mqtt-device-api-spec.md`](005-transport-mqtt-device-api-spec.md)  
**Depends on:** [`003-transport-core-spec.md`](003-transport-core-spec.md)、[`004-transport-http-device-api-spec.md`](004-transport-http-device-api-spec.md)  
**TB 基线：** 本地 `../thingsboard/`，commit `684f92bbfd`（`MqttTransportHandler` / `JsonMqttAdaptor` / `MqttTopics`）

> **状态：已完成。**

## 设计原则

- **Adapter 薄层**：Netty MQTT 帧 ↔ 已有 `TransportService` / `TransportRpcService` / `TransportDeviceApiService`；业务规则不复制。
- **ASYNC Session**：MQTT 持久连接；HTTP SYNC 行为不变。
- **主题**：硬编码 TB `MqttTopics` v1 + v2 OTA chunk；默认 JSON UTF-8。
- **不过度设计**：单 `MqttTransportHandler` + `MqttTopics` + `MqttSessionListener`；无独立 Adaptor 接口树、无 Gateway/Sparkplug。

## 实现映射

| 区域 | 类 |
|------|-----|
| 主题常量 | `mqtt.MqttTopics` |
| 配置 / 启停 | `mqtt.MqttTransportProperties`、`mqtt.MqttTransportConfiguration` |
| Netty Broker | `mqtt.MqttTransportServer`、`mqtt.MqttTransportHandler` |
| 下行 PUBLISH | `mqtt.MqttSessionListener`（实现 `SessionMsgListener`） |
| Core ASYNC | `TransportSessionRegistry.registerAsync`、`TransportService.registerAsyncSession` |
| 领域复用 | `JsonConverter`、`TransportDeviceApiService`、`TransportRpcService` |

## Slice 0: Core ASYNC Session

- Goal: MQTT 连接期间 `deliver*` 不关闭 Session；HTTP 长轮询仍单次关闭。
- Acceptance: （基础设施，支撑 AC-1、AC-5）
- Depends on: 003 done
- Test or proof: `TransportSessionIntegrationTest` 增 ASYNC 用例：deliver 后 Session 仍 active
- Implementation outline:
  - `TransportSessionRegistry` — `SessionMode.SYNC|ASYNC`；`registerAsync(...)`；`deliver*` 仅在 SYNC 时 `CloseReason.DELIVERED`
  - `subscribedToRpc` 标志（对齐 `subscribedToAttributes`）
  - `TransportService.registerAsyncSession` + `DefaultTransportService`
- Verification: `mvn test -Dtest=TransportSessionIntegrationTest`
- Done: true

## Slice 1: CONNECT 鉴权 + Broker 骨架

- Goal: `transport.mqtt.enabled=true` 时 TCP 监听；ACCESS_TOKEN / MQTT_BASIC CONNECT；失败 CONNACK 拒绝。
- Acceptance: AC-1
- Depends on: Slice 0
- Test or proof: `MqttDeviceConnectIntegrationTest`（HiveMQ client + 随机端口）
- Implementation outline:
  - `pom.xml` — `netty-codec-mqtt`；测试 `hivemq-mqtt-client`
  - `MqttTopics`、`MqttTransportProperties`（`enabled`、`bind-address`、`port` 默认 1883、`idle-timeout`）
  - `MqttTransportServer` + `MqttTransportHandler` — CONNECT/DISCONNECT/PING
  - 鉴权：`DeviceCredentialsService.authenticateAccessToken` / `authenticateMqttCredentials`；provision 连接（clientId/username=`provision`）
  - 成功：`registerAsyncSession` + `MqttSessionListener`
- Verification: `mvn test -Dtest=MqttDeviceConnectIntegrationTest`
- Done: true

## Slice 2: 遥测 + CLIENT 属性上行

- Goal: PUBLISH `devices/me/telemetry|attributes` → Main Queue。
- Acceptance: AC-2, AC-3
- Depends on: Slice 1
- Test or proof: `MqttDeviceTelemetryAttributeIntegrationTest`
- Implementation outline:
  - `MqttTransportHandler.processPublish` — 主题路由 + `JsonConverter` + `TransportService.process`
  - QoS 0/1 PUBACK
- Verification: `mvn test -Dtest=MqttDeviceTelemetryAttributeIntegrationTest`
- Done: true

## Slice 3: 属性读闭环

- Goal: PUBLISH `attributes/request/{id}` + SUBSCRIBE `attributes/response/+` → JSON 同 HTTP GET attributes。
- Acceptance: AC-4
- Depends on: Slice 2
- Test or proof: `MqttDeviceAttributesIntegrationTest`
- Implementation outline:
  - 解析 request body `clientKeys`/`sharedKeys`（对齐 TB `JsonMqttAdaptor`）
  - `MqttSessionListener.onGetAttributesResponse` → PUBLISH response topic
- Verification: `mvn test -Dtest=MqttDeviceAttributesIntegrationTest`
- Done: true

## Slice 4: 服务端→设备 RPC

- Goal: SUBSCRIBE `rpc/request/+`；管理端 twoway；PUBLISH `rpc/response/{id}`；Session 保持。
- Acceptance: AC-5
- Depends on: Slice 0, Slice 1
- Test or proof: `MqttDeviceRpcIntegrationTest`
- Implementation outline:
  - SUBSCRIBE 时 `TransportRpcService.activateRpcSubscription(sessionInfo)`
  - `MqttSessionListener.onMessage` → PUBLISH `devices/me/rpc/request/{id}`
  - PUBLISH response → `TransportRpcService.replyToRpc`（复用 pendingTwoway）
- Verification: `mvn test -Dtest=MqttDeviceRpcIntegrationTest`
- Done: true

## Slice 5: 设备→云 RPC

- Goal: PUBLISH `rpc/request/{id}` + SUBSCRIBE `rpc/response/+`。
- Acceptance: AC-6
- Depends on: Slice 4
- Test or proof: 同上 IT 或 `MqttDeviceToServerRpcIntegrationTest`
- Implementation outline:
  - `ToServerRpcRequestMsg(requestId, method, params)` from topic + body
  - `onToServerRpcResponse` → PUBLISH `rpc/response/{id}`
- Verification: `mvn test -Dtest=MqttDeviceToServerRpcIntegrationTest`
- Done: true

## Slice 6: claim + provision

- Goal: claim JSON；provision 专用连接 + `provision/request|response`。
- Acceptance: AC-7, AC-8
- Depends on: Slice 1
- Test or proof: `MqttDeviceClaimProvisionIntegrationTest`
- Implementation outline:
  - claim → `TransportDeviceApiService.registerClaimingInfo`
  - provision → `TransportDeviceApiService.provision` + `MqttSessionListener` 或 inline PUBLISH response
- Verification: `mvn test -Dtest=MqttDeviceClaimProvisionIntegrationTest`
- Done: true

## Slice 7: OTA 设备主题分片

- Goal: `devices/me/firmware|software/request/{id}/chunk/{n}` ↔ `devices/me/firmware|software/response/+/chunk/+`。
- Acceptance: AC-9
- Depends on: Slice 1, Slice 4（004 OTA 逻辑）
- Test or proof: `MqttDeviceOtaIntegrationTest`
- Implementation outline:
  - 复用 `TransportDeviceApiService.downloadOta` 取 chunk 字节
  - PUBLISH binary 到 response topic；meta/title/version 不匹配 → error topic 或空载荷（对齐 TB）
- Verification: `mvn test -Dtest=MqttDeviceOtaIntegrationTest`
- Done: true

## Slice 8: MQTT 5 / QoS 2 / quota / OTA hardening

- **Goal:** 保留 TCP/TLS/WebSocket listeners；统一 MQTT 5 properties、QoS 2 四步状态机、OTA metadata 校验和 TenantUsage 限流/配额错误映射。
- **Acceptance:** AC-11..AC-15（新增）；错误不得静默成功；QoS 2 重复 packet identifier 不重复执行写入。
- **Implementation:** `MqttTransportHandler` 解析 CONNECT properties、发送 reason-coded ACK、处理 PUBREC/PUBREL/PUBCOMP；`MqttSessionListener` 分配合法 packet identifier、跟踪下行 in-flight QoS；OTA 复用 `TransportDeviceApiService` 并拒绝无效 metadata；租户限流/配额仍由 `TenantUsageService` 执行，Adapter 映射 MQTT 5 reason code。
- **Verification:** MQTT 3/5 真实客户端集成测试；QoS 2 duplicate、invalid OTA、quota exceeded、WebSocket smoke。
- **Done:** in progress

## AC → Slice

| AC | Slice |
|----|-------|
| AC-1 | 1 |
| AC-2, AC-3 | 2 |
| AC-4 | 3 |
| AC-5 | 4 |
| AC-6 | 5 |
| AC-7, AC-8 | 6 |
| AC-9 | 7 |
| AC-10 | 全部（包路径 + 无新表） |
| AC-11..AC-15 | 8 |

## Plan 自检

| 检查项 | 结论 |
|--------|------|
| 每个 AC 有 Slice | ✅ |
| 无 Gateway/Sparkplug/v2-short/Protobuf | ✅ 排除在 Spec Non-goals |
| 不复制 claim/provision/OTA 规则 | ✅ 复用 `TransportDeviceApiService` |
| Core ASYNC 前置 | ✅ Slice 0 |
| 测试用真实 MQTT 客户端 | ✅ HiveMQ client IT |
| X509 | ⏸ Spec 可选切片，本 Plan 不含 |

## Risks / Dependencies

- **Netty MQTT** 为新增依赖；Spring Boot 管理 Netty 版本。
- **测试端口**：IT 使用 `server.port=0` 式随机 MQTT 端口，避免与本地 1883 冲突。
- **`TransportRpcService.subscribeToRpc`** 保持 HTTP SYNC；MQTT 走 `activateRpcSubscription`，勿改 HTTP 语义。
