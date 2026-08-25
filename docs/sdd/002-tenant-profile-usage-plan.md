# Tenant Profile & Usage Plan

**Spec:** `docs/sdd/002-tenant-profile-usage-spec.md`

> 交付顺序 002。  
> **状态：Profile Slice 1–2 + Usage Slice 3–5 均已完成且 AC 验收通过。**

## Slice 1–2: Profile（已完成）

AC-1..5；Done: true.

## Slice 3: Usage 查询 API + 状态

- Goal: `GET /api/tenants/{tenantId}/usage`、`GET /api/tenants/{tenantId}/usage`；实体 COUNT + 月计数；状态机。
- Acceptance: AC-6, AC-8, AC-9, AC-10
- Test: `TenantUsageHttpIntegrationTest`
- Implementation: `V58__tenant_usage_counter`；`TenantUsageService`/`Controller`；`USAGE_READ`
- Verification: `mvn -q -Dtest=TenantUsageHttpIntegrationTest test`
- Done: true

## Slice 4: 实体创建配额执行

- Goal: 新建 Device/Customer/User 超限 → 403；EXCEEDED 可见。
- Acceptance: AC-7
- Test: 同上 IT
- Implementation: `DeviceService`/`CustomerService`/`UserService` 调 `requireEntityQuota`
- Verification: `mvn -q -Dtest=TenantUsageHttpIntegrationTest,TenantProfileHttpIntegrationTest test`
- Done: true

## Slice 5: 运行时计量 / 限流 / warn 通知

- Goal: Telemetry/Mail/SMS 自动 increment；周期超限 403；Profile rate-limit 字符串 Redis 限流 429；达 WARNING/EXCEEDED 站内通知 + 邮件。
- Acceptance: AC-11, AC-12, AC-13, AC-14
- Test: `TenantUsageRuntimeHttpIntegrationTest`（及 Usage IT 回归）
- Implementation: `TenantRateLimitService`；Telemetry/Mail/SMS 挂钩；阈值事件监听（**过渡**可含 `tenant_notification` / 租户级 Inbox）
- **008 迁移：** [`008`](008-notification-center-spec.md) Phase-1 验收后，warn 投递改 `NotificationCenter` + 用户级 Inbox；drop `tenant_notification` 与 `com.roseboard.tenant.notification`（见 008 AC-8）
- Verification: `mvn -q -Dtest=TenantUsageHttpIntegrationTest,TenantUsageRuntimeHttpIntegrationTest test`
- Done: true

## AC → Slice

| AC | Slice |
|----|-------|
| AC-1..5 | 1–2 |
| AC-6,8,9,10 | 3 |
| AC-7 | 4 |
| AC-11..14 | 5 |

## Build log

- 2026-08-19: Profile Slice 1–2 验收。
- 2026-08-19: Usage Slice 3–4 落地 — usage API、状态机、实体配额 403、`increment`；Verification **exit 0**（Usage 4/4；Profile 3/3）。
- 2026-08-19: Usage Slice 5 — Telemetry/Mail/SMS 计量、Redis rate-limit 429、warn 站内通知；Verification **exit 0**。
