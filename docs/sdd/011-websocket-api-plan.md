# WebSocket API Implementation Plan

**Spec:** [`011-websocket-api-spec.md`](011-websocket-api-spec.md)  
**Depends on:** JWT / API Key 鉴权（Security）；Telemetry / Attribute 读模型；Device 租户隔离（`DeviceService.requireScoped`）  
**TB 基线：** 本地 `../thingsboard/`，commit `684f92bbfd`（`TbWebSocketHandler` / `DefaultWebSocketService` / `WebSocketConfiguration`）

> **状态：已批准。** Phase 1 已验收（2026-08-20）。  
> Spec：[`011-websocket-api-spec.md`](011-websocket-api-spec.md)（已批准）  
> Phase 2 Spec/Plan：[`012-websocket-api-phase2-spec.md`](012-websocket-api-phase2-spec.md) / [`012-websocket-api-phase2-plan.md`](012-websocket-api-phase2-plan.md)（**已批准**，2026-08-20）

## 路径交付状态

| Path | Slice | 状态 | 备注 |
|------|-------|------|------|
| `/api/ws`（GENERAL） | 1 | ✅ | `WsCommandsWrapper` |
| `/api/ws/plugins/telemetry`（TELEMETRY） | 2 | ✅ | `TelemetryCmdsWrapper` → 统一 `WebSocketService` |
| `/api/ws/plugins/notifications`（NOTIFICATIONS） | — | ⏸ | Phase 2，依赖 008 |
| `?token=` 预鉴权 | 1 | ✅ | query JWT |
| 首包 `AuthCmd`（JWT / API Key） | 1 | ✅ | 无效 token → 关闭 |
| auth 超时关闭 | 1 | ✅ | 默认 10s，`server.ws.auth_timeout_ms` |
| v1 `TIMESERIES` / `ATTRIBUTES` / `TIMESERIES_HISTORY` | 3–5 | ✅ | 仅 `DEVICE` 实体 |
| 领域变更 → WS push | 3–4 | ✅ | Spring `@EventListener` |
| v2 `ENTITY_DATA` / `ALARM_*` | — | ⏸ | Phase 2 |
| Tenant Profile WS 配额 / rate limit | — | ⏸ | Phase 2，当前 no-op |

### AC 状态

| AC | 状态 | 测试 |
|----|------|------|
| AC-1 JWT 鉴权 / 无效 token | ✅ | `#authWithValidJwtKeepsConnectionOpen`、`#invalidJwtClosesConnection` |
| AC-1 API Key 鉴权 | ✅ | `#authWithValidApiKeyKeepsConnectionOpen`、`#authWithValidApiKeyCanSubscribeTimeseries`、`#invalidApiKeyClosesConnection` |
| AC-2 TIMESERIES 推送 | ✅ | `#timeseriesSubscriptionReceivesPushOnTelemetryWrite` |
| AC-3 ATTRIBUTES 推送 + 越权 | ✅ | `#attributesSubscriptionReceivesPushOnAttributeChange`、`#unauthorizedDeviceSubscriptionReturnsErrorUpdate` |
| AC-4 TIMESERIES_HISTORY | ✅ | `#timeseriesHistoryReturnsPoints` |
| AC-5 auth 超时 | ✅ | `#authTimeoutClosesConnection` |
| AC-6 telemetry 插件路径 | ✅ | `#telemetryPluginPathWithQueryTokenWorks` |
| AC-7 包路径 / 无 Flyway / JSON 兼容 | ✅ | 代码审查 + IT 绿 |

```bash
mvn -q -Dtest=WebSocketApiIntegrationTest test
```

## 实现映射（Roseboard）

| 区域 | 类 / 组件 |
|------|-----------|
| WS 配置 | `com.roseboard.infrastructure.websocket.WebSocketConfiguration` |
| Handler + 出站端点 | `RoseboardWebSocketHandler`（`TextWebSocketHandler` + `WebSocketMsgEndpoint`） |
| 命令编排 | `WebSocketService` / `DefaultWebSocketService` |
| 内存订阅表 | `WebSocketSubscriptionRegistry` |
| 领域事件 → push | `WebSocketPushListener` |
| 命令 DTO | `cmd/*`（`WsCommandsWrapper`、`AuthCmd`、v1 cmd、`TelemetryCmdsWrapper`） |
| 响应 DTO | `sub/TelemetrySubscriptionUpdate` |
| Security 放行 | `SecurityConfiguration.WS_ENTRY_POINT` = `/api/ws/**` |
| 遥测写入事件 | `DeviceTelemetrySavedEvent` ← `TelemetryService.persistValidated` |
| 属性变更事件 | `DeviceAttributeChangedEvent` ← `DeviceAttributeService` save/update |

**Phase 1 数据流：**

```text
连接 /api/ws 或 /api/ws/plugins/telemetry
  → RoseboardWebSocketHandler（path → WebSocketSessionType；?token= 或 AuthCmd）
  → DefaultWebSocketService.handleCommands
  → WebSocketSubscriptionRegistry（注册 cmdId + deviceId + keys）
  → 初次 snapshot：TelemetryService.latest / DeviceAttributeService.find*

设备写入 telemetry / attribute（HTTP/MQTT 等，与 WS 无关）
  → TelemetryService / DeviceAttributeService 发布领域事件
  → WebSocketPushListener
  → WebSocketSubscriptionRegistry.match*
  → DefaultWebSocketService.sendUpdateBySessionId
  → RoseboardWebSocketHandler.send（出站队列 → TextMessage）
```

**与 Transport Core 边界：** WS 不使用 `TransportSessionRegistry`；管理面订阅与设备传输会话完全分离（对齐 TB）。

## Slice 1：骨架 + Auth + Handler

- **Goal:** 暴露 `/api/ws/**`；JWT / API Key 鉴权；auth 超时；出站队列；Pong 处理。
- **Acceptance:** AC-1、AC-5、AC-7（Handler 层）
- **Depends on:** Security 已 permit `/api/ws/**`
- **Test:** `WebSocketApiIntegrationTest` auth / timeout / invalid token
- **Implementation:**
  - `WebSocketConfiguration` — `@EnableWebSocket`，mapping `/api/ws/**`，`allowedOriginPatterns("*")`
  - `RoseboardWebSocketHandler` — `toRef` path 解析；pending / authenticated session 表；`ScheduledExecutorService` auth 超时
  - `WebSocketSessionRef`、`WebSocketSessionType`、`WebSocketMsgEndpoint`
  - `JwtAuthenticationProvider.authenticateAccessToken`、`ApiKeyAuthenticationProvider.authenticateApiKey`
  - 配置项（`@Value` 默认）：`server.ws.auth_timeout_ms=10000`、`server.ws.max_queue_messages_per_session=1000`、`server.ws.ping_timeout=30000`
  - `@Lazy WebSocketService` 打破 Handler ↔ Service 循环依赖
- **Verification:** AC-1、AC-5 IT 绿
- **Done:** true

## Slice 2：插件路由（Telemetry）

- **Goal:** `/api/ws/plugins/telemetry` 解析 `TelemetryCmdsWrapper`，与 GENERAL 共用 `WebSocketService`。
- **Acceptance:** AC-6
- **Depends on:** Slice 1
- **Test:** `WebSocketApiIntegrationTest#telemetryPluginPathWithQueryTokenWorks`
- **Implementation:**
  - `TelemetryCmdsWrapper.toCommonCmdsWrapper()` — 合并 `tsSubCmds` / `attrSubCmds` / `historyCmds`
  - Handler `parseCommands` 按 `WebSocketSessionType` 分支
  - `?token=` 预鉴权后可直接发插件载荷（无 `authCmd`）
- **Verification:** AC-6 IT 绿
- **Done:** true

## Slice 3：v1 TIMESERIES + 推送

- **Goal:** 订阅 Device 遥测键；写入后 push TB 兼容 update；越权 error。
- **Acceptance:** AC-2、AC-3（越权部分）、AC-7
- **Depends on:** Slice 1
- **Test:** `#timeseriesSubscriptionReceivesPushOnTelemetryWrite`、`#unauthorizedDeviceSubscriptionReturnsErrorUpdate`
- **Implementation:**
  - `TimeseriesSubscriptionCmd`、`DefaultWebSocketService.handleTimeseries`
  - `WebSocketSubscriptionRegistry.registerTimeseries` / `matchTelemetry`（空 keys = 全键）
  - `DeviceTelemetrySavedEvent` + `WebSocketPushListener.onTelemetrySaved`
  - `DeviceService.requireScoped` → `SubscriptionErrorCode.UNAUTHORIZED`（errorCode=2）
- **Verification:** 设备 HTTP telemetry → WS 收到 `{subscriptionId, data}` update
- **Done:** true

## Slice 4：v1 ATTRIBUTES + 推送

- **Goal:** 订阅属性 scope/keys；变更 push；与 REST 读一致。
- **Acceptance:** AC-3
- **Depends on:** Slice 3（共用 Registry / PushListener）
- **Test:** `#attributesSubscriptionReceivesPushOnAttributeChange`
- **Implementation:**
  - `AttributesSubscriptionCmd`、`handleAttributes`（scope：`CLIENT_SCOPE` 等 → `AttributeScope`）
  - `DeviceAttributeChangedEvent` + `WebSocketPushListener.onAttributeChanged`
  - 空 keys 订阅 → 注册时返回当前 snapshot，后续匹配该 scope 任意键变更
- **Verification:** 设备 HTTP attributes → WS push
- **Done:** true

## Slice 5：v1 TIMESERIES_HISTORY

- **Goal:** 单次历史查询，响应形状对齐 TB v1。
- **Acceptance:** AC-4
- **Depends on:** Slice 1
- **Test:** `#timeseriesHistoryReturnsPoints`
- **Implementation:**
  - `GetHistoryCmd`、`DefaultWebSocketService.handleHistory`
  - 复用 `TelemetryService.history`（与 REST 同源）
- **Verification:** IT 断言 `data.temperature` 非空数组
- **Done:** true

## AC → Slice

| AC | Slice |
|----|-------|
| AC-1 | 1 |
| AC-5 | 1 |
| AC-6 | 2 |
| AC-2 | 3 |
| AC-3 | 3、4 |
| AC-4 | 5 |
| AC-7 | 1–5 |

## 配置

| 属性 | 默认 | 说明 |
|------|------|------|
| `server.ws.auth_timeout_ms` | `10000` | 未鉴权连接关闭超时 |
| `server.ws.max_queue_messages_per_session` | `1000` | 单 session 出站队列上限 |
| `server.ws.ping_timeout` | `30000` | 空闲超时（Pong 刷新 activity） |
| `server.ws.max_text_message_buffer_size` | `32768` | Servlet 容器文本缓冲 |
| `server.ws.max_binary_message_buffer_size` | `32768` | Servlet 容器二进制缓冲 |

测试可覆盖：`registry.add("server.ws.auth_timeout_ms", () -> 2000)`（见 `WebSocketApiIntegrationTest`）。

## 响应 JSON 形状（Phase 1）

**成功 update：**

```json
{
  "subscriptionId": 7,
  "data": {
    "temperature": [[1735689600000, "33.3"]]
  }
}
```

**错误 update：**

```json
{
  "subscriptionId": 9,
  "errorCode": 2,
  "errorMsg": "Device tenant scope denied"
}
```

`subscriptionId` 与请求 `cmdId` 相同（对齐 TB `TelemetrySubscriptionUpdate`）。

## 已知简化（相对 TB）

| TB 能力 | Roseboard Phase 1 |
|---------|-------------------|
| `SessionEvent` / 租户 session 限额 | 未实现；鉴权成功 = 连接保持 |
| 主动 WebSocket Ping 帧 | 未实现；仅 Pong 处理 + 空闲超时 |
| `AUTH_SUCCESS` 响应帧 | 未实现；AC-1 以连接保持为准 |
| `RateLimitService` WS updates | no-op |
| Tenant Profile `wsMsgQueueLimitPerSession` | 仅用全局 `max_queue_messages_per_session` |
| 异步 native `AsyncRemote` + SendHandler | Spring 同步 `session.sendMessage` + 内存队列 |
| v2 EntityData / Alarm 命令 | 返回 `BAD_REQUEST`（Phase 2） |
| NOTIFICATIONS 插件 | 连接可建立；**首条消息**解析时 `BAD_DATA` 关闭（Phase 2 Slice 4 改） |
| API Key WS 鉴权 | ✅ `WebSocketApiIntegrationTest` |

## Phase 2（见独立 Spec / Plan）

- **Spec/Plan（已批准）：** [`012-websocket-api-phase2-spec.md`](012-websocket-api-phase2-spec.md) / [`012-websocket-api-phase2-plan.md`](012-websocket-api-phase2-plan.md)
- **切片方向：** 2a Entity/Alarm wire → 2b Notifications 插件 → 2c Tenant WS 配额 → 2d 集群（optional，006）
- **硬依赖：** Entity Query → 012 Plan Slice 0；009（Alarm）；008（Inbox）；002（Profile 限额字段）

## 实施钉死项

1. **包路径固定** `com.roseboard.infrastructure.websocket`；**无新 Flyway 表**。
2. **实体类型 Phase 1 仅 `DEVICE`**；其他 `entityType` → error。
3. **推送触发** 必须来自领域事件（`DeviceTelemetrySavedEvent` / `DeviceAttributeChangedEvent`），禁止 WS 层轮询 DB。
4. **JSON 字段名** 与 TB 对外 wire 兼容：`authCmd`、`cmds`、`type`、`cmdId`、`subscriptionId`、`errorCode`、`errorMsg`、`data`。
5. **Transport 分离：** 不得将 WS session 注册进 `TransportSessionRegistry`。
6. **循环依赖：** Handler `@Lazy` 注入 `WebSocketService`；Service `@Lazy` 注入 `WebSocketMsgEndpoint`。

## Revision log

- 2026-08-20 | Spec 已批准 | Phase 1 验收 | 012 Phase 2 Spec 草案
- 2026-08-20 | Phase 1 五 Slice 验收完成 | AC-1..7 映射 | 已实施
- 2026-08-20 | 初稿 | 对齐 011 Spec Phase 1 | 已批准
