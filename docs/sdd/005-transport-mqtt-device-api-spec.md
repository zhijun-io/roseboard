# Roseboard Transport MQTT Device API Spec

> **状态：已实现。**  
> Plan：[`005-transport-mqtt-device-api-plan.md`](005-transport-mqtt-device-api-plan.md)

## Goal

在 **已落地的 Transport Core**（[`003`](003-transport-core-spec.md)）与 **HTTP 设备面**（[`004`](004-transport-http-device-api-spec.md)）之上，完成 ThingsBoard **MQTT 设备传输**迁移：CONNECT 鉴权、`devices/me/*` 主题上的遥测/属性/RPC/claim/provision/OTA，语义与载荷对齐 TB `MqttTransportHandler` / `JsonMqttAdaptor` / `MqttTopics`（JSON 默认）。

## Dependencies

- Requires: **已验收** [`003-transport-core-spec.md`](003-transport-core-spec.md)；[`004`](004-transport-http-device-api-spec.md) 已验证的领域行为（Telemetry/Attribute/RPC/claim/provision/OTA 可复用 `TransportDeviceApiService` / `TransportRpcService` 等）；`DeviceCredentialService`（`ACCESS_TOKEN`、`MQTT_BASIC`；X509 可选切片）；Device Profile `transportPayloadType` 等价配置（默认 JSON）。
- Requires（Core 扩展，见 Constraints）: **ASYNC 持久会话**——MQTT 连接存活期间 `deliver` 不得像 HTTP 长轮询一样单次投递后关闭 Session。
- Provides: Netty MQTT Broker 适配层（`com.roseboard.infrastructure.transport.mqtt`）；Roseboard `devices/me` 设备主题表（语义对齐 TB）。
- Does not own: Gateway（[`010`](010-gateway-subdevices-spec.md)）、Sparkplug B、CoAP/LwM2M、Rule Engine、003 Queue 内核。

## TB 基线（本地源码）

与 [`004`](004-transport-http-device-api-spec.md) 相同 commit：`684f92bbfd`（`../thingsboard/`）。

| 对照文件 | 路径 |
|----------|------|
| MQTT Handler | `../thingsboard/common/transport/mqtt/.../MqttTransportHandler.java` |
| JSON 适配 | `../thingsboard/common/transport/mqtt/.../adaptors/JsonMqttAdaptor.java` |
| 主题常量 | `../thingsboard/common/data/.../device/profile/MqttTopics.java` |
| HTTP 等价行为 | `../thingsboard/common/transport/http/.../DeviceApiController.java` |

## Scope

### 传输与鉴权

- 独立 MQTT 监听（默认 **1883** TCP、**8883** TLS、**8083** WebSocket）；`roseboard.transport.mqtt.enabled` 开关。
- **CONNECT** 鉴权对齐 TB：
  - **ACCESS_TOKEN**：`username` = device access token（password 可空），或 token 仅放在 password（与 TB `ValidateBasicMqttCred` 一致）。
  - **MQTT_BASIC**：`clientId` / `userName` / `password` → `DeviceCredentialsService.authenticateMqttCredentials`。
  - **Provision 专用连接**：`clientId` 或 `username` = `provision` → 仅允许 `provision/request` / `provision/response`，不得访问 `devices/me/*`。
  - **X509**（TLS 连接）：客户端证书 SHA3-256 指纹 → `authenticateX509Certificate`（对齐 TB `processX509CertConnect`）；无证书时回退 token 鉴权。
- 鉴权失败：**CONNACK 拒绝**（对齐 TB `CONNECTION_REFUSED_NOT_AUTHORIZED` / MQTT 5 reason）。
- 已连接会话：`registerAsyncSession` + `recordActivity`；**DISCONNECT** / 空闲超时 → `deregisterSession`。

### 设备主题（JSON，与 `MqttTopics` 一致）

**设备已鉴权连接：**

| 方向 | Topic | TB 语义 | HTTP 等价 |
|------|-------|---------|-----------|
| PUB | `devices/me/telemetry` | 遥测 JSON | `POST .../telemetry` |
| PUB | `devices/me/attributes` | CLIENT 属性 JSON | `POST .../attributes` |
| PUB | `devices/me/attributes/request/{requestId}` | 读属性（body 含 client/shared keys） | `GET .../attributes` |
| SUB | `devices/me/attributes/response/+` | 属性读响应 | GET attributes 响应 |
| SUB | `devices/me/rpc/request/+` | 服务端→设备 RPC | `GET .../rpc` |
| PUB | `devices/me/rpc/response/{requestId}` | 设备 RPC 回复 JSON | `POST .../rpc/{id}` |
| PUB | `devices/me/rpc/request/{requestId}` | 设备→云 RPC | `POST .../rpc` |
| SUB | `devices/me/rpc/response/+` | 设备→云 RPC 回复 | POST rpc 响应体 |
| PUB | `devices/me/claim` | claiming JSON | `POST .../claim` |

**无设备 token（provision 连接或匿名 publish）：**

| 方向 | Topic | 语义 |
|------|-------|------|
| PUB | `provision/request` | provision JSON |
| SUB | `provision/response` | provision 结果 JSON |

**OTA（设备主题下，使用完整资源名）：**

| 方向 | Topic | 语义 |
|------|-------|------|
| PUB | `devices/me/firmware/request/{requestId}/chunk/{chunk}` | 固件分片请求（JSON meta） |
| SUB | `devices/me/firmware/response/+/chunk/+` | 固件分片响应 |
| PUB | `devices/me/software/request/{requestId}/chunk/{chunk}` | 软件分片请求（JSON meta） |
| SUB | `devices/me/software/response/+/chunk/+` | 软件分片响应 |

（HTTP OTA 的 title/version 校验语义不变；MQTT 使用设备主题下的完整 firmware/software 资源名与 requestId/chunk 帧。）

### 适配层边界

- **Adapter 只做**：Netty MQTT 帧 ↔ `TransportService` / `TransportRpcService` / `TransportDeviceApiService`；主题路由；QoS ACK。
- **禁止**：Adapter 内同步直写 Telemetry/Attribute 领域；禁止 duplicate HTTP 业务规则。
- 上行 telemetry/attributes：**必须** `TransportService.process` → Main Queue（003）。
- GET attributes / RPC / claim / provision / OTA：**复用** 004 已落地服务逻辑；MQTT 仅换传输绑定（PUBLISH/SUBSCRIBE 替代 HTTP 响应体）。
- 下行 RPC / shared 属性更新：Core `deliver*` → `MqttSessionListener` **PUBLISH** 到已 SUB 主题；**不得**关闭 MQTT 会话。

### 验收方式

- 集成测试使用 **真实 MQTT 客户端**（如 HiveMQ MQTT Client / Netty emqx client）连嵌入式 Broker；禁止仅 mock Core。
- 测试默认 `roseboard.queue.provider=memory`（与 004 一致）。

## Non-goals
- **Gateway** `v1/gateway/*`（[`010`](010-gateway-subdevices-spec.md)）。
- **Sparkplug B**、`spBv1.0/*` 主题。
- **v2 短主题**（`v2/t/j`、`v2/a/p` 等）与 **Protobuf MQTT 载荷**（可后续 Spec 扩展；本 Spec 默认 JSON v1）。
- **集群跨节点 MQTT Session 亲和**（[`006`](006-cluster-runtime-spec.md)）。

本次增强明确纳入 MQTT WebSocket、MQTT 5、QoS 2、OTA 状态校验和传输限流；这些能力不得通过回退为静默成功来规避。

## Current Context

- [`003`](003-transport-core-spec.md)：`TransportSessionRegistry` 当前 **SYNC** 语义——`deliver` / `deliverAttributeUpdate` 成功后 **关闭** Session（为 HTTP 长轮询设计）。
- [`004`](004-transport-http-device-api-spec.md)：HTTP `/api/http` 11 路由 + 管理端 RPC 已落地；`TransportDeviceApiService` 覆盖 claim/provision/OTA。
- Roseboard 已有 `DeviceCredentialsService.authenticateMqttCredentials` / `authenticateAccessToken` / `authenticateX509Certificate`。
- TB 设备 MQTT 使用 **持久会话 + 主题订阅**；HTTP 使用 **SYNC 长轮询**——Roseboard 需 Core 区分两种 Session 模式。

## Requirements

1. MQTT Broker 启用时可接受 CONNECT；凭证校验对齐 TB；失败 CONNACK 拒绝、无 DeviceInfo 泄露。
2. Core 提供 **ASYNC Session**（见 Constraints）；MQTT 连接生命周期内可多轮 `deliver` 而不关闭 Session。
3. `devices/me/telemetry` 与 `devices/me/attributes` PUBLISH 成功后遥测/CLIENT 属性经 Queue 可查（与 004 AC-1/3 等价）。
4. 属性读：PUBLISH request + SUBSCRIBE response 闭环；响应 JSON 形状对齐 TB `JsonMqttAdaptor` / HTTP GET attributes。
5. 服务端→设备 RPC：SUBSCRIBE `rpc/request/+` 后管理端 twoway 可收到含 **整型 requestId** 的 JSON；PUBLISH `rpc/response/{id}` 后管理端收到回复（与 004 AC-6/7 等价）。
6. 设备→云 RPC：PUBLISH `rpc/request/{id}` + SUBSCRIBE `rpc/response/+` 闭环（与 004 AC-14 等价）。
7. claim / provision / OTA MQTT 主题行为与 004 HTTP 等价（claim→SERVER `claimingData`；provision JSON；OTA 分片）。
8. 集成测试覆盖 AC；至少一条端到端 MQTT 客户端用例。
9. MQTT 5 CONNECT 返回标准 CONNACK 属性；协议错误、payload 错误、限流和配额错误使用 reason code，不得静默成功。
10. QoS 2 PUBLISH 必须完成 PUBREC → PUBREL → PUBCOMP；重复 packet identifier 不得重复执行业务写入；服务端下行 QoS 2 完成 PUBREC/PUBREL/PUBCOMP。
11. OTA 请求必须校验 topic 中 requestId/chunk 与 JSON metadata（title/version/size）及已分配包一致；无效请求不得返回成功 ACK。
12. MQTT 上行 telemetry/attributes 复用 `TenantUsageService` 的租户/设备速率窗口和周期配额；超限时 MQTT 5 返回 `QUOTA_EXCEEDED`，MQTT 3 客户端收到失败 ACK 后不得写入。
13. TCP、TLS、WebSocket、WebSocket TLS 四种 listener 必须共享 MQTT 5/QoS/限流/OTA 语义。

## Acceptance Criteria

- AC-1: When 设备以合法 **ACCESS_TOKEN** CONNECT（username=token）, then CONNACK 成功且 Session 注册为 ASYNC；When 非法 token, then CONNACK 拒绝且未注册 Session。
- AC-2: When 已连接设备 PUBLISH `devices/me/telemetry` 合法 JSON, then 200/QoS ACK 且 Telemetry 经 Queue 可查；When 未连接或鉴权失败, then 不写入。
- AC-3: When PUBLISH `devices/me/attributes` 合法 JSON, then CLIENT 属性可回读；行为与 004 AC-3 等价。
- AC-4: When PUBLISH `.../attributes/request/{id}` 并 SUBSCRIBE `.../attributes/response/+`, then 收到与 HTTP GET attributes 等价的 JSON（无 secret/凭证字段）。
- AC-5: When SUBSCRIBE `.../rpc/request/+` 且管理端 twoway 投递, then 设备收到含整型 id/method/params 的 JSON；PUBLISH `.../rpc/response/{id}` 后管理端收到同一回复；Session **保持连接**。
- AC-6: When PUBLISH `.../rpc/request/{id}`（设备→云）并 SUBSCRIBE `.../rpc/response/+`, then 收到服务端回复 JSON；缺字段 → 适配层错误 ACK/丢弃（不 silent success）。
- AC-7: When PUBLISH `.../claim`, then SERVER 侧可观察 `claimingData`（与 004 AC-9 等价）。
- AC-8: When provision 连接 PUBLISH `provision/request` 合法 key/secret, then SUBSCRIBE `provision/response` 收到与 HTTP provision 等价的 JSON（`status`/`credentialsType`/`credentialsValue`）。
- AC-9: When MQTT v2 OTA 请求已分配包且 title/version（meta）匹配, then 分片响应字节与 HTTP firmware/software 下载一致；不匹配 → 错误 topic/空载荷（对齐 TB）。
- AC-10: When 实现本 Spec, then 无新业务 Flyway 表、无新 `device.*` Entity；MQTT 包位于 `infrastructure.transport.mqtt`；复用 004 领域服务，不复制 claim/provision/OTA 规则。
- AC-11: MQTT 5 client CONNECT 后 CONNACK 含 receive maximum、maximum QoS、maximum packet size；非法 payload/topic/权限/限流使用非成功 reason code。
- AC-12: QoS 2 上行完成 PUBREC → PUBREL → PUBCOMP；重复 PUBLISH packet identifier 只确认且业务写入最多一次；服务端 QoS 2 下行完成对应握手。
- AC-13: OTA metadata 缺 title/version/size、chunk 非法、或 package 不匹配时不发送成功 ACK/数据；合法 metadata 返回精确 chunk。
- AC-14: 触发租户或设备 transport rate limit / period quota 时不写入 telemetry/attributes，MQTT 5 收到 `QUOTA_EXCEEDED`。
- AC-15: TCP、TLS、WebSocket、WebSocket TLS listener 的 MQTT 5、QoS 2、OTA 和限流行为一致。

## Constraints

- **Core 前置（003 扩展）**：新增 `registerAsyncSession(SessionInfo, SessionMsgListener, Duration idleTimeout)`（或等效 SessionMode）；ASYNC 模式下 `deliver` / `deliverAttributeUpdate` / `deliverToServerRpcResponse` **不得** auto-close Session；仅 DISCONNECT / idle / 新连接顶替时关闭。HTTP SYNC 行为保持不变。
- 默认载荷 **JSON**（UTF-8）；主题名 **逐字** 采用 TB `MqttTopics` v1/v2 OTA 常量。
- QoS：支持 QoS 0/1/2；QoS 1 使用 PUBACK，QoS 2 使用 PUBREC/PUBREL/PUBCOMP；重复 QoS 2 PUBLISH 只确认不重复执行业务。
- MQTT 5：解析 CONNECT properties，发送 CONNACK receive maximum / maximum QoS / maximum packet size；错误 ACK 带 reason code。
- 限流/配额：使用 `TenantUsageService` 与 `TenantRateLimitService` 既有窗口/周期语义，Adapter 只映射错误为 MQTT reason code。
- 编解码：MQTT JSON 与 HTTP 共用 `JsonConverter` / `TransportService.process` 消息类型。
- 单设备单活跃 ASYNC Session（新 CONNECT 顶替旧连接，对齐 TB）。
- 实现勿引入 second Queue 拓扑；上行/下行仍走 003 Main / TransportNotifications。

## Decisions

- Chosen: **先 v1 JSON 全功能**，与 004 HTTP parity；Gateway/Sparkplug/v2-short/Protobuf 排除在外。
- Chosen: OTA 走 MQTT **v2 chunk** 主题（TB 标准）；不单做 MQTT 二进制 HTTP-redirect。
- Chosen: 业务逻辑 **复用** `TransportDeviceApiService` / `TransportRpcService`，MQTT 只做主题绑定（避免重复实现）。
- Rejected: 在 MQTT Adapter 内重写 claim/provision/OTA 规则；用 HTTP SYNC Session 冒充 MQTT 持久连接；迁移 TB `TransportProtos` 全量。

## Open Questions

（无 — Core ASYNC Session 扩展纳入本 Spec Constraints，Plan 中作为 Slice 0。）

## Revision log

- 2026-08-20 | 初稿 | AC-1..10 | 待批准
- 2026-08-20 | TLS + WebSocket + roseboard 配置前缀 | AC-1 扩展 | 已实现
