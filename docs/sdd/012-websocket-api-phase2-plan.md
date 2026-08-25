# WebSocket API Phase 2 Implementation Plan

**Spec:** [`012-websocket-api-phase2-spec.md`](012-websocket-api-phase2-spec.md)  
**Depends on:** [`011-websocket-api-plan.md`](011-websocket-api-plan.md) Phase 1 ✅；[`008-notification-center-plan.md`](008-notification-center-plan.md) Inbox；[`002-tenant-profile-usage-plan.md`](002-tenant-profile-usage-plan.md) Profile JSON
**Blocked（可选后续）:** [`009-alarm-event-spec.md`](009-alarm-event-spec.md) + **Alarm 领域实现**（Slice 3/6 **已冻结**）；[`006`](006-cluster-runtime-spec.md) 全量服务发现（Slice 7 已用 Redis ws.push 最小实现）
**TB 基线：** 本地 `../thingsboard/`，commit `684f92bbfd`（`DefaultWebSocketService` v2 handlers、`NotificationCmdsWrapper`、`TbWebSocketHandler.checkLimits`）

> **状态：已批准，Slice 0–2、4–5、7 ✅。** **Slice 3 / 6 已冻结**（Roseboard 无 Alarm 领域）。  
> Spec：[`012-websocket-api-phase2-spec.md`](012-websocket-api-phase2-spec.md)（已批准，2026-08-20）  
> **回归：** 每个 Slice 合并前 `WebSocketApiIntegrationTest`（011 Phase 1）必须绿。

## Spec Phase（2a–2d）↔ Plan Slice

| Spec | Plan Slice | 说明 |
|------|------------|------|
| 2a Entity wire | 0–2 | 0=EntityQuery；1–2=ENTITY_* |
| 2a Alarm wire | 3、6 | **🔒 冻结** — 无 Alarm 领域 + 009 未落地 |
| 2b Notifications | 4 | 插件 + Inbox push |
| 2c Quota / rate limit | 5 | Profile + Valkey |
| 2d 集群 | 7 | ✅ | Redis ws.push |

## 路径交付状态

| 能力 | Slice | 状态 | 备注 |
|------|-------|------|------|
| Entity Query 最小读模型 | 0 | ✅ | `com.roseboard.device.query.EntityQueryService` |
| v2 DTO + `@JsonSubTypes` 扩展 | 1 | ✅ | `cmd/v2/*` |
| `ENTITY_DATA` snapshot + 增量 push | 1–2 | ✅ | latest 优先；ts 窗口后置 |
| `ENTITY_COUNT` + unsubscribe | 2 | ✅ | 对齐 REST device count |
| `ALARM_*` stub | 3 | 🔒 **冻结** | Roseboard 无 Alarm 领域；不实施 |
| `ALARM_*` 真实 wire | 6 | 🔒 **冻结** | 009 + Alarm 领域落地后重开 |
| `/api/ws/plugins/notifications` | 4 | ✅ | `NotificationCmdsWrapper` |
| Tenant Profile WS session 限额 | 5 | ✅ | Profile JSON 新字段 |
| WS update rate limit | 5 | ✅ | Valkey + `server.ws.updates_rate_limit` |
| 跨节点 WS push | 7 | ✅ | Redis pub/sub `roseboard:ws:push` |

### AC 状态（012 Spec）

| AC | Slice | 状态 | 测试（计划） |
|----|-------|------|--------------|
| AC-2a-1 ENTITY_DATA + push | 1–2 | ✅ | `WebSocketV2EntityDataIntegrationTest` |
| AC-2a-2 ENTITY_COUNT | 2 | ✅ | 同上 |
| AC-2a-3 ALARM | 3 | 🔒 **冻结** | 不纳入 Phase 2 验收 |
| AC-2a-4 ALARM push | 6 | 🔒 **冻结** | 009 + 领域落地后重开 |
| AC-2b-1 NOTIFICATIONS push | 4 | ✅ | `WebSocketNotificationsIntegrationTest` |
| AC-2b-2 MARK_READ | 4 | ✅ | 同上 |
| AC-2c-1 tenant session 上限 | 5 | ✅ | `WebSocketQuotaIntegrationTest` |
| AC-2c-2 TOO_MANY_UPDATES | 5 | ✅ | 同上 |
| AC-2-7 无 WS Flyway / 包路径 | 0–7 | ✅ | 审查 + 011 回归 |

```bash
# Phase 1 回归（每个 Slice 必跑）
mvn -q -Dtest=WebSocketApiIntegrationTest test

# Slice 0
mvn -q -Dtest=EntityQueryServiceTest,EntityQueryServiceIntegrationTest test

# Phase 2 Slice 2 目标
mvn -q -Dtest=WebSocketApiIntegrationTest,WebSocketV2EntityDataIntegrationTest test
```

## Open Questions 决议（Plan 钉死）

| # | Spec 问题 | Plan 决议 |
|---|-----------|-----------|
| 1 | Entity Query 归属 | **不单独开 Spec**；Slice 0 在 `com.roseboard.query` 交付 `EntityQueryService`（最小子集），WS 仅调用 |
| 2 | Dynamic page link refresh | **不做**；仅 snapshot + 领域事件增量 |
| 3 | ALARM / 009 | **Slice 3 / 6 冻结** — Roseboard **无 Alarm 领域**（无表、无 `AlarmService`、无变更事件）。Phase 2 **不**注册 `ALARM_*` parser/handler；客户端发 `ALARM_*` → `Unsupported command type`。009 批准 **且** Alarm 领域落地后 **重新授权** Slice 6（可选 Slice 3 友好 stub） |
| 4 | Rate limit 后端 | **复用** Valkey + 新 key 前缀 `ws:updates:{sessionId}`；不引入 TB `LimitedApi` 枚举 |

## 实现映射（Phase 2 增量）

| 区域 | 类 / 组件 |
|------|-----------|
| Entity 读模型 | `com.roseboard.device.query.EntityQueryService`（新建） |
| Filter DTO | `com.roseboard.device.query.filter.SingleEntityFilter`、`DeviceTypeFilter` |
| v2 命令 DTO | `infrastructure.websocket.cmd.v2.*`（`EntityDataCmd`、`EntityCountCmd`、unsubscribe、update） |
| 命令注册 | 扩展 `WsCommandsWrapper` / `TelemetryCmdsWrapper` `@JsonSubTypes` |
| v2 订阅表 | `WebSocketEntitySubscriptionRegistry`（或扩展现有 Registry 分域） |
| v2 编排 | `DefaultWebSocketService` 新 handler；或 `WebSocketV2CommandHandler` 委托 |
| Notification 插件 | `cmd/plugin/NotificationCmdsWrapper`；Handler 放开 `NOTIFICATIONS` path |
| Notification push | `NotificationCreatedEvent` / `NotificationReadEvent` → `WebSocketNotificationPushListener` |
| Profile 限额 | `TenantProfileWsLimits` 读 `profileData.configuration`；`RoseboardWebSocketHandler.checkLimits` |
| Rate limit | `WebSocketUpdateRateLimiter`（Valkey 滑动窗口） |

**Phase 2a 数据流（ENTITY_DATA）：**

```text
ENTITY_DATA cmd（SingleEntityFilter + LatestValueCmd）
  → DefaultWebSocketService.handleEntityData
  → EntityQueryService.snapshotLatest(deviceId, keys)
  → EntityDataUpdate（首次）
  → WebSocketEntitySubscriptionRegistry.register

DeviceTelemetrySavedEvent / DeviceAttributeChangedEvent
  → WebSocketPushListener（扩展 match entity subscriptions）
  → EntityDataUpdate 增量（仅变更键）
  → Handler.send
```

## Slice 0：Entity Query 最小读模型

- **Goal:** 为 v2 WS / 未来 REST 共用：单实体 snapshot + DeviceType 计数；**不在 WS 包内写 SQL**。
- **Acceptance:** AC-2a-1 / AC-2a-2 的前置（无独立 AC；由 Slice 2 IT 覆盖）
- **Depends on:** `DeviceService`、`TelemetryService`、`DeviceAttributeService`、`DeviceMapper`
- **Test:** `EntityQueryServiceTest`（单元）；由 Slice 2 IT 集成验证
- **Implementation:**
  - `EntityQueryService.countDevices(tenantId, customerId, DeviceTypeFilter)`
  - `EntityQueryService.loadEntityData(tenantId, deviceId, EntityDataKeys)` — latest telemetry + 三 scope attributes
  - Filter 校验：unsupported filter type → `IllegalArgumentException`（WS 层转 `BAD_REQUEST`）
  - **不支持：** relation filter、asset、dynamic page link、aggregation
- **Verification:** 单元测试 + Slice 2 IT
- **Done:** true

## Slice 1：v2 线协议 + ENTITY_DATA snapshot

- **Goal:** 扩展 JSON 解析；`ENTITY_DATA` 返回 TB 形状 snapshot；暂不 push。
- **Acceptance:** AC-2a-1（snapshot 部分）、AC-2-7
- **Depends on:** Slice 0
- **Test:** `WebSocketV2EntityDataIntegrationTest#entityDataReturnsLatestSnapshot`
- **Implementation:**
  - 包 `cmd/v2/`：`EntityDataCmd`、`EntityDataQuery`（Roseboard 精简）、`LatestValueCmd`、`EntityDataUpdate`、`CmdUpdateType`
  - `WsCmdType` 扩展；`WsCommandsWrapper` / `TelemetryCmdsWrapper` 注册 v2 types
  - `DefaultWebSocketService`：`case ENTITY_DATA -> handleEntityData`
  - 注册 subscription（为 Slice 2 push 做准备）
  - 越权：`DeviceService.requireScoped`
- **Verification:** WS 连接 → `ENTITY_DATA` → JSON 含 `cmdId` + `data` page（单实体）
- **Done:** true

## Slice 2：ENTITY_DATA 增量 push + ENTITY_COUNT

- **Goal:** telemetry/attribute 变更 push `EntityDataUpdate`；`ENTITY_COUNT` 与 REST count 一致；unsubscribe。
- **Acceptance:** AC-2a-1（push）、AC-2a-2
- **Depends on:** Slice 1
- **Test:** `WebSocketV2EntityDataIntegrationTest`（push + count + unsubscribe）
- **Implementation:**
  - 扩展 `WebSocketPushListener` 或 `WebSocketEntityPushListener` match `ENTITY_DATA` 订阅
  - `ENTITY_COUNT` / `ENTITY_COUNT_UNSUBSCRIBE` handler
  - `EntityCountUpdate` 响应；设备 create/delete 时可 push（`DeviceLifecycleEvent` 可选，或 count 仅 on-demand 首包——**Plan 钉死：首包 on subscribe + 设备增删事件 push**）
  - `ENTITY_DATA_UNSUBSCRIBE` / `ENTITY_COUNT_UNSUBSCRIBE`
- **Verification:** 写 telemetry → WS `EntityDataUpdate`；创建设备 → count update
- **Done:** true

## Slice 3：ALARM_* stub — 🔒 **已冻结（不实施）**

- **Goal:** ~~客户端发 `ALARM_*` 得明确 error~~ **不交付。** Roseboard 无 Alarm 领域；Phase 2 不注册 `ALARM_*` 命令。
- **Acceptance:** AC-2a-3 **冻结**，不纳入 Phase 2 验收。
- **当前行为:** 客户端发 `ALARM_*` → 与任意未支持 v2 type 相同 → `BAD_REQUEST`（`Unsupported command type`）。
- **解冻条件:** [`009`](009-alarm-event-spec.md) 批准 **且** Alarm 领域（存储 + 服务 + 变更事件）落地；届时可重开 Slice 3（友好 stub）或直接 Slice 6。
- **Done:** N/A（冻结）

## Slice 4：Notifications 插件

- **Goal:** `/api/ws/plugins/notifications`；未读 push + mark-read。
- **Acceptance:** AC-2b-1、AC-2b-2
- **Depends on:** 008 Inbox（`NotificationService`、`NotificationController`）；Slice 1 插件解析模式可复用
- **Test:** `WebSocketNotificationsIntegrationTest`
- **Implementation:**
  - `NotificationCmdsWrapper.toCommonCmdsWrapper()`（对齐 TB 字段：`unreadSubCmd`、`unreadCountSubCmd`、`markAsReadCmd`…）
  - `RoseboardWebSocketHandler`：`NOTIFICATIONS` session 不再 `BAD_DATA` 关闭
  - `DefaultWebSocketService`：`NOTIFICATIONS`、`NOTIFICATIONS_COUNT`、`MARK_*`、`NOTIFICATIONS_UNSUBSCRIBE`
  - 领域事件：**新增** `NotificationSavedEvent`（或等价）于 `NotificationService` / `NotificationCenter` 写入路径；当前 **无** 现成事件
  - mark-read 调 `NotificationService.markRead`（与 REST 同 service）
  - 仅 `recipient_id == currentUser.id` 推送
- **Verification:** `NotificationCenter.notify` → 同用户 WS 收到未读；mark-read → count update
- **Done:** true

## Slice 5：Tenant Profile WS 配额 + Update Rate Limit

- **Goal:** session 连接数限制 + 单 session update 限速（TB 行为子集）。
- **Acceptance:** AC-2c-1、AC-2c-2
- **Depends on:** Slice 1 Handler 稳定；002 Profile 读路径
- **Test:** `WebSocketQuotaIntegrationTest`
- **Implementation:**
  - Profile configuration 扩展（**复用 `profileData.configuration` jsonb**，新 key 与 TB 对齐）：
    - `maxWsSessionsPerTenant`、`maxWsSessionsPerCustomer`、`maxWsSessionsPerRegularUser`
    - `wsMsgQueueLimitPerSession`（覆盖全局默认）
  - `RoseboardWebSocketHandler.establishAuthenticatedSession` 前 `checkLimits`（对齐 TB 计数 map + synchronized）
  - `WebSocketUpdateRateLimiter`：`server.ws.updates_rate_limit` 默认 optional；超限 → `SubscriptionErrorCode` 新增 `TOO_MANY_UPDATES`（对齐 TB errorCode）
  - Handler `send` 路径检查 rate limit；blacklist session 行为对齐 TB（发一条 error 后跳过直至恢复）
  - Flyway：**无 WS 表**；若 Profile JSON 无 schema 变更则 **无 migration**（仅文档化 key）
- **Verification:** profile `maxWsSessionsPerTenant=1` → 第二连接关闭； flood update → `TOO_MANY_UPDATES`
- **Done:** true

## Slice 6：ALARM_* 真实 wire — 🔒 **已冻结（不实施）**

- **Goal:** `ALARM_COUNT`、`ALARM_STATUS` 订阅 + 告警变更 push。
- **Acceptance:** AC-2a-4 **冻结**，009 + Alarm 领域落地后重开。
- **Depends on:** [`009`](009-alarm-event-spec.md) 批准并实现 **Alarm 领域**（非仅 WS wire）
- **Test:** `WebSocketV2AlarmIntegrationTest`（解冻后）
- **Implementation:** （解冻后）`AlarmQueryService` / `AlarmChangedEvent`；v2 `AlarmCountCmd`、`AlarmStatusCmd`；`ALARM_DATA` 后置
- **Done:** N/A（冻结）

## Slice 7：集群 WS 广播

- **Goal:** 多节点下 push 到达 **持有订阅 session 的节点**（本地 registry 不迁移）。
- **Acceptance:** 集群模式开启后，telemetry/attribute/notification 变更经 `ws.push` 广播，各节点仅向本地 session 投递。
- **Depends on:** Slice 2–4 单节点稳定；共享 Redis（Valkey）
- **Implementation:**
  - `server.ws.cluster.enabled=true` 启用 Redis pub/sub（channel 默认 `roseboard:ws:push`）
  - `WebSocketPushTransport`：`LocalWebSocketPushTransport`（默认）/ `RedisWebSocketPushTransport`（集群）
  - `WebSocketPushDeliveryService` — 本地 subscription match + send（从 `WebSocketPushListener` 抽出）
  - `WebSocketPushRedisSubscriber` — 各节点订阅 channel，local delivery
  - 006 全量服务发现 / Queue 分区 **后置**；当前不迁移 subscription registry
- **Test:** `WebSocketClusterPushIntegrationTest`
- **Verification:** cluster 模式 telemetry 写入 → WS 收到 push（Redis 往返）
- **Done:** true

## AC → Slice

| AC | Slice |
|----|-------|
| AC-2a-1 | 0、1、2 |
| AC-2a-2 | 0、2 |
| AC-2a-3 | —（冻结） |
| AC-2a-4 | —（冻结） |
| AC-2b-1、AC-2b-2 | 4 |
| AC-2c-1、AC-2c-2 | 5 |
| AC-2-7 | 0–5 |
| 011 回归 | 全部 |

## 建议交付顺序

```text
Slice 0 → 1 → 2 ✅
         ↘ 4（008 已就绪，可与 2 并行）
Slice 5（最后，避免限额干扰 2/4 调试）
Slice 3、6 — 🔒 冻结（无 Alarm 领域）；009 + 领域落地后单独立项
Slice 7 — ✅ Redis ws.push 广播
```

## 配置（Phase 2 增量）

| 属性 | 默认 | 说明 |
|------|------|------|
| `server.ws.updates_rate_limit` | empty（不限） | 形如 `1000:3600` 对齐 002 rate limit 语法 |
| Profile `maxWsSessionsPerTenant` 等 | `0` | 0 = unlimited（TB 语义） |
| `server.ws.cluster.enabled` | `false` | 启用 Redis ws.push 广播 |
| `server.ws.cluster.push-channel` | `roseboard:ws:push` | Redis pub/sub channel |

## v2 响应形状（参考）

**EntityDataUpdate（TB 兼容字段）：**

```json
{
  "cmdId": 1,
  "data": {
    "data": [
      {
        "entityId": { "entityType": "DEVICE", "id": "..." },
        "latest": { "TIME_SERIES": { "temperature": { "ts": 1735689600000, "value": "26" } } }
      }
    ],
    "totalPages": 1,
    "totalElements": 1
  }
}
```

（精确嵌套以 TB `EntityDataUpdate` / `WebSocketApiTest` 断言为准；Slice 1 实施时从 TB DTO 字段 1:1 拷贝。）

## 实施钉死项

1. **011 Phase 1 不破坏：** 每 Slice PR 跑 `WebSocketApiIntegrationTest`。
2. **Entity Query 在 `com.roseboard.query`**，WS 禁止直接扩 `DeviceMapper` SQL。
3. **`ALARM_*` 冻结：** Roseboard 无 Alarm 领域；**不**注册 parser/handler；**不** mock 空数据；行为等同 `Unsupported command type`。
4. **Notification** 仅当前用户；不得按 tenant 广播 Inbox。
5. **无 WS 订阅持久化表**；重启丢订阅。
6. **Dynamic page link / relation query** 明确返回 `BAD_REQUEST` + 原因。
7. **v2 cmd 类** 放 `cmd/v2`；插件 wrapper 放 `cmd/plugin`。
8. **Entity 标识分层：** 领域/query 用 `UUID` + 字符串 `entityId`；**不**引入 TB `EntityId`。TB 形状 `{entityType, id}` 仅 `cmd/v2/EntityIdRef` + wire filter；见 [Spec Decisions](012-websocket-api-phase2-spec.md#decisions)。

## Revision log

- 2026-08-20 | Slice 7 验收 | Redis ws.push 集群广播 | 已实施
- 2026-08-20 | Slice 5 验收 | WS session 限额 + update rate limit | 已实施
- 2026-08-20 | Slice 4 验收 | Notifications 插件 + push + mark-read | 已实施
- 2026-08-20 | ALARM WS 冻结 | Slice 3/6 不授权；无 Alarm 领域 | Spec + Plan
- 2026-08-20 | Entity 标识分层 | Spec Decisions + Plan 钉死项 #8 | 文档
- 2026-08-20 | Slice 2 验收 | ENTITY_DATA push + ENTITY_COUNT | 已实施
- 2026-08-20 | Slice 1 验收 | v2 DTO + ENTITY_DATA snapshot | 已实施
- 2026-08-20 | Slice 0 验收 | EntityQueryService | 已实施
- 2026-08-20 | **已批准** | Slice 0–2、4–5 可实施；3/6 冻结 | Open Questions 决议入 Plan
- 2026-08-20 | 初稿 | Slice 0–7 + Open Questions 决议 | 草案
