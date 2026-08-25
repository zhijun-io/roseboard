# Roseboard

IoT 设备接入与租户运营上下文：设备身份、属性/遥测、传输配置和凭证。

## Delivery phases（Spec 顺序 = 建议交付顺序）

| ID | 主题 | 状态 |
|----|------|------|
| **001** | 非传输层完善（平台 + 设备管理面 + Queue/OTA） | **已验收** [`spec`](docs/sdd/001-non-transport-domain-completion-spec.md) / [`plan`](docs/sdd/001-non-transport-domain-completion-plan.md) |
| **002** | Tenant Profile + Usage | **已验收**（含运行时计量/限流/warn） [`spec`](docs/sdd/002-tenant-profile-usage-spec.md) / [`plan`](docs/sdd/002-tenant-profile-usage-plan.md) |
| **003** | Transport Core 会话编排 | **已验收** [`spec`](docs/sdd/003-transport-core-spec.md) · [`plan`](docs/sdd/003-transport-core-plan.md) |
| **004** | HTTP `/api/http` Device API | **进行中**（Slice 1 完成：遥测/属性） [`spec`](docs/sdd/004-transport-http-device-api-spec.md) · [`plan`](docs/sdd/004-transport-http-device-api-plan.md) |
| **005** | MQTT `/api/http` Device API | **已实现** [`spec`](docs/sdd/005-transport-mqtt-device-api-spec.md) · [`plan`](docs/sdd/005-transport-mqtt-device-api-plan.md) |
| **006** | 集群与运行时 | Phase 1 **已实施** [`spec`](docs/sdd/006-cluster-runtime-spec.md) · Phase 2 **待批准** [`phase2-spec`](docs/sdd/006-cluster-runtime-phase2-spec.md) |
| **007** | 缓存一致性 | 大纲 [`spec`](docs/sdd/007-cache-consistency-spec.md) |
| **008** | Notification Center（投递中心） | **已批准** [`spec`](docs/sdd/008-notification-center-spec.md) · [`plan`](docs/sdd/008-notification-center-plan.md) |
| **009** | Alarm + Event（借鉴重做，不迁 RE） | 大纲 [`spec`](docs/sdd/009-alarm-event-spec.md) |
| **010** | Gateway 子设备 | 大纲 [`spec`](docs/sdd/010-gateway-subdevices-spec.md) |
| **011** | WebSocket API Phase 1 | **已验收** [`spec`](docs/sdd/011-websocket-api-spec.md) · [`plan`](docs/sdd/011-websocket-api-plan.md) |
| **012** | WebSocket API Phase 2 | **已批准** [`spec`](docs/sdd/012-websocket-api-phase2-spec.md) · [`plan`](docs/sdd/012-websocket-api-phase2-plan.md) |
| **013** | Queue 高阶运行时 | **已完成** [`spec`](docs/sdd/013-queue-runtime-advanced-spec.md) · [`plan`](docs/sdd/013-queue-runtime-advanced-plan.md) |

## Product decisions（相对 ThingsBoard）

| 决策 | 状态 |
|------|------|
| Rule Engine / Actor / TbMsg | **不实现** |
| **Transport Core 会话编排** | **必须迁移**（003；语义见下；**无 Actor**） |
| Tenant Profile / Usage | **要做**（[`002`](docs/sdd/002-tenant-profile-usage-spec.md)；借鉴 TB，计量点最小化） |
| 告警 / 事件 | **可做、另 Spec**（[`009`](docs/sdd/009-alarm-event-spec.md)；**不**迁 TB RE 告警链） |
| 通知投递 | **已批准、可实施**（[`008`](docs/sdd/008-notification-center-spec.md)；Slice 1 起） |
| 资产 / Edge / EntityView / Dashboard 等 | **暂不实现** |
| Gateway 子设备 | **另 Spec**（[`010`](docs/sdd/010-gateway-subdevices-spec.md)） |
| 遥测/属性等缓存与一致性 | **另 Spec**（[`007`](docs/sdd/007-cache-consistency-spec.md)） |
| 集群与运行时 | **要对齐 TB**（[`006`](docs/sdd/006-cluster-runtime-spec.md)） |
| HTTP `/api/http` 及 MQTT 等 Adapter | HTTP [`004`](docs/sdd/004-transport-http-device-api-spec.md)（依赖 003）；MQTT [`005`](docs/sdd/005-transport-mqtt-device-api-spec.md)（依赖 003/004） |
| MQTT/CoAP/LwM2M Adapter | MQTT [`005`](docs/sdd/005-transport-mqtt-device-api-spec.md)；Gateway [`010`](docs/sdd/010-gateway-subdevices-spec.md) 依赖 MQTT |


## Package layout

- **领域模型相对独立**（`device` 可划分子包）:
  - `com.roseboard.device` — Device 身份
  - `com.roseboard.device.profile` — Device Profile
  - `com.roseboard.device.credential` — Device Credentials
  - `com.roseboard.device.attribute` — Attribute
  - `com.roseboard.device.telemetry` — Telemetry（含管理端 HTTP，001）
  - `com.roseboard.device.rpc` — Persistent RPC
- **协议无关 Transport Core** → `com.roseboard.infrastructure.transport`  
  Spec: `docs/sdd/003-transport-core-spec.md`（**已验收**）
- **缓存框架** → `com.roseboard.infrastructure.cache`（`CacheTemplate`、`CacheCodec`、`CacheAutoConfiguration`）；存储 SPI 在 `cache.store`（Caffeine/Redis 实现分包）；失效在 `cache.eviction`。领域 key 与 eviction event 仍在 `com.roseboard.cache`
  Spec: `docs/sdd/007-cache-consistency-spec.md`
- **审计框架** → `com.roseboard.infrastructure.audit`（入口 `AuditTemplate` / `@Audited`，装配 `AuditAutoConfiguration`）；写入 SPI 在 `audit.writer`（JDBC 默认实现分包）；上下文在 `audit.context`；主体在 `audit.principal`；切面在 `audit.aspect`。查询 API、动作常量和 `audit_log` 实体仍在 `com.roseboard.audit`。HTTP `X-Request-Id` 由 `infrastructure.web` 装配，审计只读取 request attribute。
  Spec: `docs/sdd/014-audit-log-spec.md`
- **协议 Adapter** → `com.roseboard.infrastructure.transport.<protocol>`  
  - HTTP：`docs/sdd/004-transport-http-device-api-spec.md`（**进行中**；Slice 1 遥测/属性已落地）
  - MQTT：`docs/sdd/005-transport-mqtt-device-api-spec.md`（**已实现**）；CoAP/LwM2M：后续 Spec；Gateway：[`010`](docs/sdd/010-gateway-subdevices-spec.md)
- **Queue 框架** → `com.roseboard.infrastructure.queue`（入口 `QueueCoordinator`、`QueueAutoConfiguration`；消息/路由值对象在 `queue.model`；SPI 在 `queue.spi`；Memory/Kafka 适配在 `queue.adapter.<provider>`；配置在 `queue.config`；消费生命周期在 `queue.consumer`；处理策略在 `queue.processing`；DLQ 在 `queue.deadletter`）。领域 CRUD / HTTP 仍在 `com.roseboard.queue`
  Spec: `docs/sdd/013-queue-runtime-advanced-spec.md`；管理 HTTP：001；分区事件由 `infrastructure.cluster` 消费，不把 cluster 塞进 queue。
- **Notification** → 业务数据库与 HTTP 仍在 `com.roseboard.notification`（三表、Inbox、管理接口）；可抽成 starter 的投递框架在 `com.roseboard.infrastructure.notification`（入口 `NotificationCenter`；值对象 `model`；扩展点 `spi`；渠道适配 `channel`；默认编排 `internal`）。
- **不做** SQL inbox / outbox；协议 Session **落在内存**，不落到 SQL 表。

## Messaging（无 RE / 无 Actor）

```
上行:  Device ──▶ Adapter ──▶ Transport Core ──▶ Queue ──▶ Service（领域写入）
下行:  Domain ──▶ Queue（直发）──▶ Transport Core ──▶ 内存 Session listener ──▶ Adapter/设备
```

### Core 会话编排（TB 迁移，无 Actor）

**目标**：迁移 ThingsBoard `TransportService` 的会话编排**可观察语义**（注册同步会话、activity、按会话投递下行、超时释放），**不**迁移 Device Actor / 邮箱 / TbMsg。

| TB（`TransportService`） | Roseboard Core |
|--------------------------|----------------|
| `SessionInfo`（sessionId、device、tenant、node…） | 内存 Session 记录（同字段语义，无 Protobuf） |
| `registerSyncSession(session, listener, timeout)` | `registerSyncSession` → 挂 listener，超时注销 |
| `recordActivity(session)` | `touch` / activity 更新 |
| 下行投到 SessionMsgListener | `deliver(deviceId\|sessionId, msg)` 唤醒 listener |
| 会话结束 / 错误关闭 | `close` / 超时释放；不可再投递 |
| Device Actor 串行邮箱 | **不迁**；同进程内 listener 回调即可 |

实现要点：

1. **Session 注册表**（进程内）：`sessionId` → `{ deviceId, tenantId, protocol, listeners, requestIdSeq, lastActivity }`。
2. **SYNC 长轮询**：Adapter 调 Core `registerSyncSession(listener, timeout)`。
3. **下行**：Queue → Core `deliver` → listener（无 Session → 离线，由调用方 504 等）。
4. **整型 requestId**：会话内递增；可选映射 `device_rpc.id`。
5. **上行**：`publishUplink` → Queue（默认 JSON）；Core **不**直写领域。
6. **多节点**：粘滞/路由属**集群 Spec**；本迁移先保证**单节点语义与 TB 对齐**。

## Language

**Tenant**:
拥有隔离数据边界的运营主体。
_Avoid_: account, org

**Customer**:
租户内的客户归属；设备可分配给 Customer。
_Avoid_: client, buyer

**Device**:
租户内的物理或逻辑设备身份。不承载协议连接对象或 secret。可有 `deviceData`（JSONB）与 `externalId`（对齐 TB）。
_Avoid_: thing, endpoint（资产 Asset 暂不实现）

**Device Profile**:
设备运行策略的稳定身份。传输配置、provision 配置、LwM2M/SNMP 映射存在 `profile_data` JSON 中，每个 Profile 一种 transport type。
_Avoid_: device type template, transport profile table

**Device Credentials**:
一设备一行凭证；按 `credentialId` 认证。类型与 ThingsBoard 一致：`ACCESS_TOKEN`、`X509_CERTIFICATE`、`MQTT_BASIC`、`LWM2M_CREDENTIALS`。`credentialsValue` 明文存储（对齐 TB）。
Transport Core 须统一支持上述四类鉴权；HTTP `/api/http/{deviceToken}` 仅 ACCESS_TOKEN；其余由对应协议 Adapter 调用 Core。
_Avoid_: secret table；SNMP 凭证类型（放 Profile/transport 配置）；在 HTTP 上发明非 TB 的多凭证头

**Attribute**:
设备当前状态。Scope 为 CLIENT / SHARED / SERVER。
_Avoid_: key-value, property bag

**Telemetry**:
带时间戳的历史点；latest 是可重建投影。缓存/一致性另 Spec。
_Avoid_: event log, message payload table

**Persistent RPC** (`com.roseboard.device.rpc`):
`device_rpc` 表，形状对齐 TB `Rpc`（行 id 为 UUID）。管理端 HTTP 对齐 TB V2：`/api/rpc/...`。
设备侧 HTTP（`/api/http/.../rpc/{requestId}`）对齐 TB：**整型 requestId**；由 Transport 会话映射到 UUID 行（不新建映射表）。
_Avoid_: 非 TB 路径如 `/api/device/{id}/rpc`；把 UUID 直接当设备路径 requestId

**Transport Session**:
**内存**会话（sessionId、SYNC listener、activity、整型 requestId），无 Actor、不落 SQL。
_Avoid_: SQL transport session 表；Actor 邮箱模型；inbox/outbox

**Claim / Provision / Gateway**:
Claim→SERVER attributes；Provision→Profile `profile_data`；Gateway 子设备→[`010`](docs/sdd/010-gateway-subdevices-spec.md)（`additionalInfo` / 网关会话，**无**独立 gateway token 表为默认）。
HTTP claim/provision 随 [`004`](docs/sdd/004-transport-http-device-api-spec.md)（稍后实现）。
_Avoid_: claim/provision/gateway token 表（除非 Gateway Spec 证明必要）

**暂缓领域**（明确不做本期 / 见后续 Spec）:
Asset、Edge、EntityView、Dashboard/Widget、Notification、Rule Engine。  
Alarm/Event→[`009`](docs/sdd/009-alarm-event-spec.md)；Notification→[`008`](docs/sdd/008-notification-center-spec.md)；Tenant Profile/Usage→[`002`](docs/sdd/002-tenant-profile-usage-spec.md)；缓存→[`007`](docs/sdd/007-cache-consistency-spec.md)；集群→[`006`](docs/sdd/006-cluster-runtime-spec.md)。
