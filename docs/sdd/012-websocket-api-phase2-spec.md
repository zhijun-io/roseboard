# Roseboard WebSocket API Phase 2 Spec

> **状态：已批准，可实施（Slice 0–2、4–5 ✅）。** **Slice 3 / 6 已冻结**（Roseboard 无 Alarm 领域，见 [Decisions — Alarm](#decisions)）。Slice 7 依赖 006。  
> Plan：[`012-websocket-api-phase2-plan.md`](012-websocket-api-phase2-plan.md)（已批准，2026-08-20）。  
> 前置：[`011-websocket-api-spec.md`](011-websocket-api-spec.md) Phase 1 **已验收**。

## Goal

在 Phase 1（v1 遥测/属性 + Auth + telemetry 插件）基础上，扩展 TB 兼容的 **WebSocket v2 命令**（Entity Query）、**notifications 插件**、**Tenant Profile WS 配额**，使集成客户端（非 Roseboard 内置 UI）可订阅实体查询结果与 Inbox 未读——**仍不**承担设备传输、**Alarm 领域**或全量移植 TB `DefaultWebSocketService`。

## Dependencies

| 依赖 | 用途 | 状态 |
|------|------|------|
| [`011`](011-websocket-api-spec.md) | Handler / `WebSocketService` / 插件路由 / v1 命令 | ✅ Phase 1 已验收 |
| Entity Query 读模型 | `ENTITY_DATA` / `ENTITY_COUNT` 的 filter + pageLink | ✅ Slice 0（`EntityQueryService`） |
| [`009`](009-alarm-event-spec.md) | `ALARM_*` 订阅的数据源与变更事件 | **Roseboard 无 Alarm 领域**；Slice 3/6 **冻结**，见 Decisions |
| [`008`](008-notification-center-spec.md) | Inbox 未读、`NOTIFICATIONS*` 命令 | 已批准 |
| [`002`](002-tenant-profile-usage-spec.md) | Tenant Profile WS session / update 限额字段 | 部分已有 Usage；WS 限额字段待扩展 |
| [`006`](006-cluster-runtime-spec.md) | 跨节点 WS push 广播 | Slice 7：Redis `ws.push`；006 全量发现后置 |

**Provides：** v2 WS 命令处理；`/api/ws/plugins/notifications`；可配置的 WS rate limit / session 限额（对齐 TB `DefaultTenantProfileConfiguration` 子集）。

**Does not own：** Rule Engine；Dashboard UI；设备 Transport；**Alarm 存储 / 规则 / 生命周期（Roseboard 当前未实现，非 WS 职责）**。

## TB 基线（本地源码）

Commit：`684f92bbfd`（`../thingsboard/`）。

| 能力 | 对照文件 |
|------|----------|
| v2 命令注册 | `DefaultWebSocketService`（`handleWsEntityDataCmd` 等） |
| v2 DTO | `service/ws/telemetry/cmd/v2/*` |
| Entity 订阅 | `TbLocalSubscriptionService` / `SubscriptionManagerService` |
| Notification 插件 | `NotificationCmdsWrapper`、`WebSocketSessionType.NOTIFICATIONS` |
| 限额 | `TbWebSocketHandler.checkLimits`、`RateLimitService`（`LimitedApi.WS_UPDATES_PER_SESSION`） |
| 集成测试 | `WebSocketApiTest`（v2 history、entity count、alarm 等） |

## Scope

### Phase 2a：v2 Telemetry / Entity 命令（GENERAL + telemetry 插件）

在 `WsCommandsWrapper` / `TelemetryCmdsWrapper` 中增加 TB 兼容的 v2 `type`  discriminant：

| 命令 `type` | 方向 | 语义 | 前置 |
|-------------|------|------|------|
| `ENTITY_DATA` | C→S | 实体数据订阅（latest / ts / history 组合） | Entity Query |
| `ENTITY_DATA_UNSUBSCRIBE` | C→S | 取消 `ENTITY_DATA` | — |
| `ENTITY_COUNT` | C→S | 实体计数订阅 | Entity Query |
| `ENTITY_COUNT_UNSUBSCRIBE` | C→S | 取消 `ENTITY_COUNT` | — |
| `ALARM_*`（见下表） | C→S | 告警 WS wire | **🔒 冻结** — 无 Alarm 领域 + 009 未落地 |

**`ALARM_*`（TB wire 参考；Phase 2 不授权实施）：**

| 命令 `type` | 方向 | 语义 | 状态 |
|-------------|------|------|------|
| `ALARM_DATA` / `ALARM_DATA_UNSUBSCRIBE` | C→S | 告警列表订阅 | 🔒 冻结（009 + Alarm 领域） |
| `ALARM_COUNT` / `ALARM_COUNT_UNSUBSCRIBE` | C→S | 告警计数订阅 | 🔒 冻结 |
| `ALARM_STATUS` / `ALARM_STATUS_UNSUBSCRIBE` | C→S | 单实体告警状态 | 🔒 冻结 |

**当前行为（冻结期间）：** 客户端发送 `ALARM_*` → 与任意未注册 v2 type 相同，`BAD_REQUEST`（`Unsupported command type`）。**禁止**在 WS 层 mock 计数或空列表。

**响应形状：** 对齐 TB v2 update（`EntityDataUpdate`、`EntityCountUpdate`、`AlarmCountUpdate` 等）；`cmdId` / `errorCode` / `errorMsg` 与 v1 一致。

**Roseboard 最小 Entity Query（建议 Phase 2a 钉死）：**

- 仅支持 **单实体 filter**（`SingleEntityFilter` 等价：`entityType` + `entityId` 字符串）与 **DeviceTypeFilter**（type + name 前缀）；不支持 TB 全量 relation / asset 图。
- **入站 TB JSON** 中 `singleEntity: { entityType, id }` 由 WS 层 wire DTO 解析；**领域/query 层** 仍为 flat 字符串，见 [Decisions — Entity 标识分层](#decisions)。
- 不支持 dynamic page link 定时 refresh — **不做**（Plan 已决议）；仅首次 snapshot + 增量 push。
- 读路径复用既有 `TelemetryService` / `DeviceAttributeService` / `DeviceService`；**禁止**在 WS 层写 SQL。

### Phase 2b：Notifications 插件

| 路径 | 命令 | 前置 |
|------|------|------|
| `/api/ws/plugins/notifications` | `NotificationCmdsWrapper` → `NOTIFICATIONS`、`NOTIFICATIONS_COUNT`、`MARK_*`、`NOTIFICATIONS_UNSUBSCRIBE` | 008 Inbox |

- 鉴权：当前 JWT 用户；仅推送 **该用户** Inbox 变更（`recipient_id` = userId）。
- 与 008 `NotificationController` 读模型一致；WS 层不缓存通知正文以外的权限边界。

### Phase 2c：Tenant Profile WS 配额与 Rate Limit

对齐 TB 子集（可配置、可 no-op 默认）：

| 限额 | 配置来源 | 行为 |
|------|----------|------|
| `maxWsSessionsPerTenant` | Tenant Profile | 超限 → `CloseStatus.POLICY_VIOLATION` |
| `maxWsSessionsPerCustomer` | Tenant Profile | 同上 |
| `maxWsSessionsPerRegularUser` | Tenant Profile | 同上 |
| `wsMsgQueueLimitPerSession` | Tenant Profile（可低于全局默认） | 已有队列逻辑，读 profile 覆盖 |
| WS updates / session | Rate limit 服务 | 超限 → error update `TOO_MANY_UPDATES`（对齐 TB errorCode） |

**Depends on：** 002 Profile JSON 扩展字段 + 读取缓存（可先内存读 DB）。

### Phase 2d：集群 WS 广播

- 多节点时：telemetry / attribute / notification 变更经 **`ws.push`** 广播；各节点 **本地** subscription registry match + 投递。
- 默认 `server.ws.cluster.enabled=false`（单节点直送）；开启后使用 Redis pub/sub（channel 默认 `roseboard:ws:push`）。
- 订阅状态 **不迁移**；006 全量服务发现 / Queue 分区方案后置。

## Non-goals

- Stomp / SockJS / 设备 MQTT-over-WebSocket。
- 全量 TB `EntityDataQuery`（relation 链、复杂 key filter、aggregation 全枚举）。
- Rule Engine 驱动的告警产生（见 009 stance）。
- 新建 Flyway 表 **除非** 009/008/002 已授权表不足（WS 自身仍不建订阅持久化表）。
- Phase 2 内重构 Phase 1 v1 行为或更改 wire 字段语义。

## Current Context

- Phase 1 已交付：`RoseboardWebSocketHandler`、`DefaultWebSocketService`、v1 命令、telemetry 插件、事件 push、AC-1..7 集成测试。
- Phase 2a Entity（Slice 0–2）已交付：`ENTITY_DATA` / `ENTITY_COUNT` + push。
- **Roseboard 无 Alarm 领域实现**（无告警表、无 `AlarmService`、无告警变更事件）；`ALARM_*` WS **不在 Phase 2 授权范围**（Slice 3/6 冻结）。
- 客户端发送未注册的 v2 `type`（含 `ALARM_*`）→ `BAD_REQUEST`（`Unsupported command type`）。
- `/api/ws/plugins/notifications` today → 连接可建立（`?token=`）；**首条** `NotificationCmdsWrapper` 解析时关闭并说明不支持。
- TB 对照实现 ~1100 行 `DefaultWebSocketService`；Phase 2 按 **2a → 2b → 2c** 分 Plan slice，避免 Big Bang。

## Requirements

1. v2 命令 JSON **兼容 TB** `@JsonSubTypes` 字段名；Roseboard 不支持的 query 维度返回 **明确 error**（非 silent empty）。
2. `ENTITY_DATA` 至少覆盖：**单 Device** latest telemetry + client/server/shared attributes snapshot；后续 ts 窗口订阅可增量交付。
3. `ENTITY_COUNT` 至少覆盖：**DeviceTypeFilter** 计数；租户/客户 scope 隔离与 REST 一致。
4. **`ALARM_*` 不在 Phase 2 交付范围**（Roseboard 无 Alarm 领域；Slice 3/6 冻结）。冻结期间：**不**注册 `ALARM_*` parser/handler；**不**返回 mock 数据；行为等同 unsupported type。009 批准且 Alarm 领域落地后 **重新开项** WS wire（Plan Slice 6+）。
5. Notifications 插件在 **008 Inbox 可用** 后：未读数变更 + 新通知 push；mark-read 命令与 REST 副作用一致。
6. Tenant WS 限额：**可关闭**（0 = unlimited，对齐 TB）；集成测试至少覆盖 tenant session 上限 1 条路径。
7. 集成测试：真实 WebSocket 客户端 + JWT；v2 至少 1 条 `ENTITY_DATA` 与 1 条 `NOTIFICATIONS` 端到端（分 Slice 验收）。

## Acceptance Criteria（草案）

**Phase 2a（Entity wire）**

- AC-2a-1: When 已 auth 客户端发送 `ENTITY_DATA`（SingleEntityFilter + latest keys）, then 收到含 `cmdId` 的 `EntityDataUpdate` snapshot；When 订阅键 telemetry 变更, then 收到增量 update。
- AC-2a-2: When 发送 `ENTITY_COUNT`（DeviceTypeFilter）, then 响应 `EntityCountUpdate` 与 REST 设备 count 一致（同 tenant scope）。
- AC-2a-3: ~~`ALARM_*` stub~~ **冻结** — Roseboard 无 Alarm 领域；不纳入 Phase 2 验收（009 + 领域落地后重开）。
- AC-2a-4: When 009 **已**落地且 Alarm 领域已实现, then `ALARM_COUNT` 在告警创建/清除后 push 计数变化（**Slice 6**，单独开项）。

**Phase 2b（Notifications）**

- AC-2b-1: When 连接 `/api/ws/plugins/notifications?token=<JWT>` 并订阅 `NOTIFICATIONS`, then 同用户 Inbox 新增未读时收到 update。
- AC-2b-2: When 发送 `MARK_NOTIFICATIONS_AS_READ`, then 未读计数 subscription 收到更新且 DB 状态与 REST mark-read 一致。

**Phase 2c（Quota）**

- AC-2c-1: When Tenant Profile `maxWsSessionsPerTenant=1` 且已有 1 连接, then 第 2 连接被 `POLICY_VIOLATION` 关闭。
- AC-2c-2: When 单 session update 超过 profile / 全局 rate limit, then 收到 `TOO_MANY_UPDATES` error update（不断开连接，对齐 TB blacklisting 行为）。

**Phase 2 通用**

- AC-2-7: When 实现本 Spec, then 无 WS 专用 Flyway 表；代码仍位于 `com.roseboard.infrastructure.websocket`（v2 cmd 可置于 `cmd/v2` 子包）。

## Constraints

- 端点路径不变：`/api/ws`、`/api/ws/plugins/telemetry`、`/api/ws/plugins/notifications`。
- Phase 1 v1 命令与响应 **不得破坏**（011 AC 回归必须绿）。
- v2 订阅状态：**Phase 2a 默认单节点内存**；集群方案不得改变单节点语义。
- Alarm 领域以 **009** 为准；WS 仅在其后作为 **订阅边界** 接入，Phase 2 **不包含** Alarm 实现。
- **Entity 标识：** 领域与 query 包使用 `UUID` / 字符串 id；`EntityIdRef` 等 TB 线协议 DTO **仅限** `com.roseboard.infrastructure.websocket.cmd.v2`（见 Decisions）。

## Decisions

- Chosen: Phase 2 **拆分** 2a（entity wire）→ 2b（notifications）→ 2c（quota）→ 2d（cluster，optional）；**2a 不含 alarm wire**（Slice 3/6 冻结）。
- Chosen: Entity Query **最小子集**（SingleEntity + DeviceType），避免复刻 TB Query Engine。
- Chosen: 009 stub 期间 v2 alarm 命令 **显式 error**，不做 mock 数据。
- **Chosen: Roseboard 无 Alarm 领域 — `ALARM_*` WS 冻结。** 代码库当前 **无** 告警存储、服务或领域事件；Phase 2 **不实施** Slice 3（ALARM stub DTO/handler）。客户端发 `ALARM_*` 与发送任意未支持 type 行为一致。待 [`009`](009-alarm-event-spec.md) **批准并实现** Alarm 领域后，**重新授权** Plan Slice 6（及可选 Slice 3 友好 stub）。
- **Rejected:** 在 WS 层 mock `count: 0` / `data: []` 假装 Alarm 可用。
- **Chosen: Entity 标识分层（领域 vs 线协议）** — Roseboard **不**在领域层引入 TB 的 `EntityId` / `AliasEntityId` 多态体系；聚合根（如 `DeviceEntity`）使用 `UUID`，查询 filter（`com.roseboard.device.query.filter.SingleEntityFilter`）使用 `entityType` + **字符串** `entityId`（UUID 文本）。v1 WS 命令同样为扁平 `entityType` + `entityId` 字段。
- **Chosen: TB 形状仅存在于 WS 边界** — 客户端期望的 v2 JSON `entityId: { "entityType": "DEVICE", "id": "..." }` 由 `infrastructure.websocket.cmd.v2.EntityIdRef`（出站）与 `cmd/v2/query/SingleEntityWireFilter`（入站）承担；`EntityDataMapper` 在边界 **立即** 转为 `UUID` + `DeviceService.requireScoped`，`EntityQueryService` **不**依赖 `EntityIdRef`。
- **Rejected:** 为 Phase 2 在 `com.roseboard.common` 或 device 等领域包新增 TB 式 `EntityId` 类层次；`EntityIdRef` **不得**下沉到 `com.roseboard.query` 或 `com.roseboard.device`。
- Rejected: Phase 2 单 Spec 一次性移植 TB 全部 v2 + subscription manager + actor。
- Rejected: WS 订阅持久化到 DB（重启丢订阅，对齐 TB 单节点行为）。

## Open Questions（Plan 已决议，见 Plan）

1. **Entity Query 归属：** [`012-websocket-api-phase2-plan.md`](012-websocket-api-phase2-plan.md) Slice 0 — `com.roseboard.device.query.EntityQueryService`，不单独 Spec。
2. **Dynamic page link：** **不做**；仅 snapshot + 事件增量。
3. **009 / ALARM WS：** **Slice 3 / 6 冻结**；Roseboard 无 Alarm 领域，009 前 **不** 做 WS stub；009 + 领域落地后重开 Slice 6。
4. **Rate limit 后端：** Valkey + `ws:updates:{sessionId}`；见 Plan Slice 5。

## Revision log

- 2026-08-20 | ALARM WS 冻结 | 无 Alarm 领域；Slice 3/6 不授权 | Spec + Plan
- 2026-08-20 | Entity 标识分层 | 领域 UUID vs WS EntityIdRef | Spec Decisions 钉死
- 2026-08-20 | Slice 0 验收 | EntityQueryService + IT | 已实施
- 2026-08-20 | **已批准** | Slice 0–2、4–5 可实施；3/6 冻结 | Plan 同步
- 2026-08-20 | Plan 文档 | 012-websocket-api-phase2-plan.md | 已批准
