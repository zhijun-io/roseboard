# Roseboard WebSocket API Spec

> **状态：已批准。** Phase 1 已验收（2026-08-20）。  
> Plan：[`011-websocket-api-plan.md`](011-websocket-api-plan.md)  
> Phase 2：[`012-websocket-api-phase2-spec.md`](012-websocket-api-phase2-spec.md) / [`012-websocket-api-phase2-plan.md`](012-websocket-api-phase2-plan.md)（已批准，2026-08-20）

## Goal

对齐 ThingsBoard **管理面 WebSocket API**（`/api/ws`）：JWT/API Key 鉴权后，客户端以 **JSON 命令/响应** 订阅遥测、属性等实时更新——供 **集成客户端 / TB 兼容 UI** 使用。**不是**设备传输协议（设备走 HTTP/MQTT/CoAP）。

## Dependencies

- Requires: 已落地的 **JWT / API Key 鉴权**（`JwtTokenFactory`、Security）；Telemetry / Attribute 领域读路径；Device 租户隔离。
- Requires（可选后续）: [`008`](008-notification-center-spec.md) 通知存储（NOTIFICATIONS 命令）；[`009`](009-alarm-event-spec.md) Alarm 引擎（ALARM_* 命令）。
- Provides: Spring WebSocket 端点 `/api/ws/**`；TB 兼容的 `AuthCmd` + 命令信封。
- Does not own: 设备面 Transport（003/004/005）；Rule Engine；完整 TB UI；v2 EntityData/Alarm/Notification 命令（后续 Spec）。

## TB 基线（本地源码）

Commit：`684f92bbfd`（`../thingsboard/`）。

| 对照文件 | 路径 |
|----------|------|
| WS 配置 | `../thingsboard/application/.../WebSocketConfiguration.java` |
| Handler | `../thingsboard/application/.../controller/plugin/TbWebSocketHandler.java` |
| 服务 | `../thingsboard/application/.../DefaultWebSocketService.java` |
| 命令包装 | `../thingsboard/application/.../WsCommandsWrapper.java` |
| 集成测试 | `../thingsboard/application/.../WebSocketApiTest.java` |

**TB 端点（与 `WebSocketConfiguration` 一致）：**

| 路径 | `WebSocketSessionType` | 载荷解析 |
|------|------------------------|----------|
| `/api/ws` | `GENERAL` | `WsCommandsWrapper`（`authCmd` + `cmds[]`，cmd 含 `"type"`） |
| `/api/ws/plugins/telemetry` | `TELEMETRY` | `TelemetryCmdsWrapper` → `toCommonCmdsWrapper()`（TB 遗留插件格式） |
| `/api/ws/plugins/notifications` | `NOTIFICATIONS` | `NotificationCmdsWrapper`（Phase 2，见 [`012`](012-websocket-api-phase2-spec.md)） |

- 连接时可带 query **`?token=<JWT>`** 预鉴权（对齐 TB `TbWebSocketHandler.toRef`）。
- 未带 token 时首包须含 **`AuthCmd`**（`token` 或 `apiKey`）；超时未 auth → 关闭连接。

## Scope

### Phase 1（本 Spec 验收范围）

对齐 TB **插件路由** + **v1 Telemetry 命令** + Auth：

**插件机制（必选）：**

- 单 Handler 注册 `/api/ws/**`；按 URI path 解析 `WebSocketSessionType`（对齐 `TbWebSocketHandler.toRef`）。
- `TELEMETRY` 插件路径解析 `TelemetryCmdsWrapper`（`tsSubCmds` / `attrSubCmds` / `historyCmds`），合并为统一 `WsCommandsWrapper` 后进入同一 `WebSocketService`。
- `GENERAL` 路径直接解析 `WsCommandsWrapper`。

**v1 命令：**

| 命令 `type` | 方向 | 语义 |
|-------------|------|------|
| `AUTH` | C→S | `{ "authCmd": { "cmdId", "token" } }` 或 `apiKey` |
| `AUTH_SUCCESS` / 错误 | S→C | 鉴权成功 = **连接保持**（无单独 AUTH_SUCCESS 帧，对齐 TB 实际 wire）；失败 → 关闭或 error update |
| `TIMESERIES` | C→S | 订阅实体最新遥测键（`TimeseriesSubscriptionCmd`） |
| `ATTRIBUTES` | C→S | 订阅实体属性（`AttributesSubscriptionCmd`） |
| `TIMESERIES_HISTORY` | C→S | 单次历史查询（`GetHistoryCmd`） |
| 更新消息 | S→C | `{ "cmdId", "data": ... }` 或 errorCode/errorMsg（对齐 TB subscription update 形状） |

**Wire 格式：**

- 文本帧 JSON；外层 envelope 对齐 `WsCommandsWrapper`（`authCmd` + `cmds[]`）。
- 命令 discriminant：字段 `"type"`（与 TB `@JsonSubTypes` 一致）。
- 响应携带相同 `cmdId` 关联请求。

**鉴权与隔离：**

- JWT：解析 tenant/user/customer scope；订阅目标实体须通过既有 `DataScopeService` / Device 读权限校验。
- 未 auth 或 token 无效：关闭 session 或返回 error update（对齐 TB `UnauthorizedException` 路径）。
- **Ping/Pong**：处理客户端 Pong、空闲超时关闭；**不**主动发 WebSocket Ping 帧（见 Plan 已知简化）。

### Phase 2（扩展，见独立 Spec）

详见 [`012-websocket-api-phase2-spec.md`](012-websocket-api-phase2-spec.md)：

- Telemetry **v2**：`ENTITY_DATA`、`ENTITY_COUNT`、`ALARM_*`（依赖 entity query / [`009`](009-alarm-event-spec.md)）。
- Notification 插件 WS（依赖 [`008`](008-notification-center-spec.md)）。
- Tenant Profile WS 配额 / Rate limit 全量。
- 跨节点 WS 广播（依赖 [`006`](006-cluster-runtime-spec.md)，可选）。

### 实现边界

- **WebSocket 层**：连接管理、auth 超时、JSON 解析、rate limit 钩子（可先 no-op）。
- **Subscription 层**：内存订阅表（entityId + keys → session set）；领域变更时 push update（Telemetry latest / Attribute 变更可接现有 service 或 polling 钩子；Plan 阶段选型，Spec 只要求 **可观察推送**）。
- **禁止**：在 WS Handler 内实现 Telemetry 聚合 SQL；复用现有 `TelemetryService` / `DeviceAttributeService` 读模型。

## Non-goals

- 设备 WebSocket 传输（TB 无对等物；设备用 MQTT/HTTP）。
- 全量移植 TB `DefaultWebSocketService` 1100+ 行（按 Phase 分交付）。
- GraphQL / SSE 替代方案。
- 跨节点 WS Session 广播（[`006`](006-cluster-runtime-spec.md) 后续）。
- Mobile push / STOMP。

## Current Context

- Phase 1 **已落地**：`/api/ws/**`、v1 命令、telemetry 插件、JWT/API Key、事件驱动 push；`WebSocketApiIntegrationTest` AC-1..7 绿。
- Phase 2（v2 / notifications / 配额 / 集群）见 [`012-websocket-api-phase2-spec.md`](012-websocket-api-phase2-spec.md)。
- TB WebSocket 与 Transport Core **无关**；Roseboard 同样分离。

## Requirements

1. 暴露 `/api/ws/**`；CORS/`allowedOriginPatterns` 可配置（默认开发 `*` 对齐 TB）。
2. 连接后 **10s 内**须收到有效 `AuthCmd`，否则关闭（对齐 `server.ws.auth_timeout_ms` 默认）。
3. 鉴权成功后方可处理 `cmds`；非法实体/越权订阅返回带 `errorCode` 的 update，不推送数据。
4. `TIMESERIES` 订阅：实体 telemetry 键更新时，向订阅 session 推送 TB 兼容 update JSON。
5. `ATTRIBUTES` 订阅：实体属性变更时推送 update（scope 与 cmd 参数一致）。
6. `TIMESERIES_HISTORY`：单次返回历史点（对齐 TB `GetHistoryCmd` 响应形状）。
7. 集成测试：真实 WebSocket 客户端（如 Spring WebSocket client / Java-WebSocket）+ JWT；禁止仅 mock Service。

## Acceptance Criteria

- AC-1: When 客户端连接 `/api/ws` 并发送含合法 JWT 的 `AuthCmd`, then 收到 auth 成功且连接保持；When token 无效/过期, then 连接关闭或 error 响应，无数据推送。
- AC-2: When 已 auth 客户端发送 `TIMESERIES` 订阅某 Device 的键, then 该键写入新 telemetry 后收到含 `cmdId` 的 update JSON。
- AC-3: When 已 auth 客户端发送 `ATTRIBUTES` 订阅, then 属性变更后收到 update；越权 entityId → error update，无泄漏。
- AC-4: When 发送 `TIMESERIES_HISTORY`, then 响应 JSON 含请求时间范围内数据点（与 REST 读 telemetry 一致）。
- AC-5: When 连接后超过 auth 超时未发送 `AuthCmd`, then 服务端关闭连接。
- AC-6: When 连接 `/api/ws/plugins/telemetry?token=<JWT>` 并发送 `TelemetryCmdsWrapper`（`tsSubCmds`）, then 行为与 `/api/ws` + `WsCommandsWrapper` 等价。
- AC-7: When 实现本 Spec, then 无新 Flyway 表；WS 代码位于 `com.roseboard.infrastructure.websocket`；命令 DTO 字段名与 TB 对外 JSON **兼容**。

## Constraints

- 端点路径 **固定** `/api/ws`（与 TB 一致）；不得改为 `/ws` 等别名。
- 命令/响应 JSON：**兼容 TB** `WsCommandsWrapper` 与 v1 telemetry cmd 结构；新增字段可选，已有字段语义不得变更。
- Phase 1 **不实现** v2 `ENTITY_DATA` 等；客户端发 v2 cmd 应得到明确 error（非 silent ignore）。
- 单 session 推送队列上限可配置（默认 1000，对齐 TB `server.ws.max_queue_messages_per_session`）。
- 不使用 Transport Core SessionRegistry（WS 与会话编排分离）。

## Decisions

- Chosen: **Phase 1 = 插件路由 + AUTH + v1 TIMESERIES/ATTRIBUTES/HISTORY**；Telemetry 插件路径与 GENERAL 共用 `WebSocketService`。
- Chosen: WS 服务独立于 Transport MQTT/HTTP Adapter（TB 同样分离）。
- Rejected: 把设备 MQTT -over-WebSocket 纳入本 Spec；一次性移植 TB 全部 v2/alarm/notification 命令。

## Open Questions

（Phase 1 无。Phase 2 见 [`012-websocket-api-phase2-spec.md`](012-websocket-api-phase2-spec.md)。）

## Revision log

- 2026-08-20 | **已批准** | Phase 1 验收 | Plan + IT 绿
- 2026-08-20 | Plan 文档 | 011-websocket-api-plan.md | 已批准
- 2026-08-20 | 插件机制纳入 Phase 1 | AC-6 插件 telemetry | 待批准
- 2026-08-20 | 初稿 | AC-1..6 | 待批准
