# Roseboard Tenant Profile & Usage Spec

> **状态：Profile + Usage 切片均已验收。**  
> 编号：`002`。Plan：[`002-tenant-profile-usage-plan.md`](002-tenant-profile-usage-plan.md)。

## Goal

对齐 ThingsBoard **Tenant Profile** 配置契约，并补齐 **租户套餐用量（Usage）**：按 Profile 限额展示使用情况（进度/状态），在实体创建写路径执行配额——不照搬 TB `ApiUsageState` / 统计 Actor。

## Dependencies

- Requires: Profile 切片（`profileData` jsonb）；Tenant / Device / Customer / User；[`001`](001-non-transport-domain-completion-spec.md)。
- Provides: Usage 查询 API、实体/周期配额执行、rate-limit、warn 站内通知。
- Followed by: MQTT Adapter 计量挂接（[`005`](005-transport-mqtt-device-api-spec.md)）；通知投递中心（[`008`](008-notification-center-spec.md)）。
- Does not own: RE/JS/Edge 用量、商业计费、完整 TB ApiUsage Actor。

## Scope

### Profile 切片（已验收）

见修订前 AC-1..5：`profileData` 持久化、Info/Full、删/默认守卫。

### Usage 切片（已验收）+ 运行时扩展（本轮）

| 项 | 契约 |
|----|------|
| 自动计量 | **Telemetry 写入**（Transport 未落地前的代理）：每批 +1 `maxTransportMessages`，按点数累加 `maxTransportDataPoints`；**邮件**成功发送 → +1 `maxEmails`；**SMS**成功发送 → +1 `maxSms`（能解析到 tenantId 时） |
| 周期超限 | 写入前若 `used+delta > limit`（limit>0）→ **403** |
| Rate-limit 字符串 | 解析 TB 形 `count:seconds[,…]`；Telemetry 应用 `transportTenantTelemetryMsgRateLimit` / `…DataPoints…` / `transportDeviceTelemetryMsgRateLimit` / `…DataPoints…`；REST 对 TENANT_ADMIN/CUSTOMER_USER 分别应用 `tenantServerRestLimitsConfiguration` / `customerServerRestLimitsConfiguration`；超限 → **429** |
| Warn 通知 | 指标状态**进入** `WARNING` 或 `EXCEEDED` 时：经 [`008`](008-notification-center-spec.md) 投递——该租户每位 TENANT_ADMIN 一条 WEB Inbox + 邮件（已配置时）；同 period+metric+status 去重（Usage 侧） |

**仍不做：** TB ApiUsage Actor；MQTT 等 Adapter 计量（[`005`](005-transport-mqtt-device-api-spec.md) 后再挂同一钩子）。站内/邮件 warn 终态投递归 [`008`](008-notification-center-spec.md)。

## Current Context

- Profile：`TenantProfile*` + `profileData.configuration`。
- 创建路径：`DeviceService.save`、`CustomerService.save`、`UserService.save`。
- 术语见 [`CONTEXT.md`](../../CONTEXT.md)。

## TB Alignment

| 面 | TB | Roseboard Usage |
|----|----|-----------------|
| 套餐上限 | Profile configuration | 同字段名 JSON |
| 用量可见 | API Usage dashboard | `GET /api/tenants/{tenantId}/usage*` 精简列表 |
| warn | warnThreshold ≈80% | 同；默认 0.8 |
| 超限 | 禁用能力 | 实体创建 / 周期写入 403；速率限流 429 |
| 计量 Actor | ApiUsage* | **简单计数表 + 实时 COUNT**；Telemetry/Mail/SMS 挂钩 |

## Requirements

（Profile 1–5 已交付。）

6. 租户管理员可查询本租户当月用量列表（实体实时 + 周期计数）。
7. 系统管理员可按 tenantId 查询任意租户用量；跨租户 TENANT_ADMIN 拒绝。
8. 状态机按上表计算；`smsEnabled=false` 时 `maxSms` 为 DISABLED。
9. 新建设备/客户/用户超过 Profile 实体上限时拒绝（403）。
10. 周期指标可通过服务内 `increment` 累加；无调用时 used=0。

## Acceptance Criteria

### Profile（已验收）

- AC-1 … AC-5：保持不变（见 Plan Profile 切片）。

### Usage

- AC-6: When TENANT_ADMIN GET `/api/tenants/{tenantId}/usage`, then 200 且 `items` 含 `maxDevices`/`maxCustomers`/`maxUsers`，`used` 与库内 COUNT 一致，`period` 为当前 UTC `YYYY-MM`。
- AC-7: When Profile `maxDevices=1` 且租户已有 1 台设备后再 POST 创建设备, then **403**；When SYS_ADMIN GET `/api/tenants/{tenantId}/usage` 且该租户 devices used≥limit, then 对应项 `status` 为 `EXCEEDED`。
- AC-8: When `configuration.warnThreshold=0.5` 且 used/limit≥0.5 且未达上限, then 该项 `status` 为 `WARNING`；When `smsEnabled=false`, then `maxSms.status` 为 `DISABLED`。
- AC-9: When TENANT_ADMIN GET `/api/tenants/{tenantId}/usage/{otherTenantId}`, then 拒绝（403/404 范围外）。
- AC-10: When 对租户调用 UsageService.increment(`maxTransportMessages`, n) 后 GET usage, then 该项 `used` 增加 n（同月）。
- AC-11: When 租户 Telemetry `saveBatch` 成功写入 k 个点, then `maxTransportMessages` +1 且 `maxTransportDataPoints` +k；When 已达周期上限再写, then 403。
- AC-12: When 配置了 `transportTenantTelemetryMsgRateLimit` 且窗口内超限, then Telemetry 写返回 **429**。
 AC-13: When 用量状态进入 WARNING 或 EXCEEDED, then 经 Notification Center 向该租户每位 TENANT_ADMIN 投递未读 WEB 通知（及已配置时的邮件）；同 period/metric/status 不重复。（终态契约见 [`008`](008-notification-center-spec.md)；过渡实现可仍为租户表，008 Phase-1 验收后以用户 Inbox 为准。）
 AC-14: When 成功发送租户上下文下的邮件或 SMS, then 对应 `maxEmails` / `maxSms` 计数 +1。**例外：** 系统通知（`auth.*`、用量阈值）经 [`008`](008-notification-center-spec.md) `NotifyOptions.countUsage=false` 时不 increment（008 AC-7、AC-36）。

## Constraints

- 不新增 configuration 字段名；key 与 Profile 字段对齐。
- 禁止引入 Actor / TbMsg / 完整 api_usage_* TB 表结构。
- 尽量少类型：Usage 响应用 record；计数一张表。
- 实体配额仅在 **新建** 时检查（更新不占新名额）。

## Decisions

- Chosen: 实体指标实时 COUNT；周期指标按月计数表。
- Chosen: 超限 HTTP **403**（权限/配额拒绝），非 429（429 留给速率限制）。
- Deferred: 用量重置 API；MQTT Adapter 计量挂接。
- Chosen: Transport 未落地期间以 Telemetry 写作为 transport 计量代理。

## Out of Scope

- Alarm/Event 领域（[`009`](009-alarm-event-spec.md)）；Notification Center 实现细节（[`008`](008-notification-center-spec.md)，Usage 仅作调用方）；Gateway；缓存 Spec；MQTT Adapter 计量。

## Revision log

- 2026-08-19 | stub → Profile 切片完整 Spec | AC-1..5 | plan impact yes
- 2026-08-19 | Profile 切片验收通过 | none — clarification | no
- 2026-08-19 | 展开 Usage 切片 AC-6..10 | AC-6..10 | plan impact yes — 追加 Plan Usage slices
- 2026-08-19 | Usage 切片验收通过 | none — clarification | no
- 2026-08-19 | 扩展运行时计量/限流/warn 通知 | AC-11..14 | plan impact yes
- 2026-08-19 | AC-13 与 008 对齐（每 TENANT_ADMIN 一条；投递归 008） | AC-13 | no — Usage 行为观察点不变，Inbox 形状归 008
- 2026-08-19 | AC-14 交叉引用 008 `countUsage=false` | AC-14 | no
