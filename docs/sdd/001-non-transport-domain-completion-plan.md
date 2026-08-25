# Non-Transport Domain Completion Plan

**Spec:** `docs/sdd/001-non-transport-domain-completion-spec.md`

> 交付顺序 001。002–013 另见 CONTEXT；本 Plan **不** Build 传输（003/004/005）。
> **状态：Slice 1–4 已完成且 AC 验收通过。**

## Slice 1: 管理端遥测 HTTP

- Goal: DEVICE 子集 keys / latest / history / write / delete（无聚合）。
- Acceptance: AC-1
- Depends on: None
- Test or proof: `TelemetryAdminHttpIntegrationTest`
- Implementation: `TelemetryAdminController` → `TelemetryService` + Device 数据范围。
- Verification: `mvn -q -Dtest=TelemetryAdminHttpIntegrationTest test`
- Done: true

## Slice 2: Attribute 管理端补齐

- Goal: 批量读、keys、删 + 单测。
- Acceptance: AC-2
- Depends on: None（可与 Slice 1 并行）
- Test or proof: `DeviceAttributeControllerTest`；领域 IT `AttributeIntegrationTest` 不回归
- Implementation: 扩展 `DeviceAttributeController`；Service 已有 `read`/`keys`/`delete`。
- Verification: `mvn -q -Dtest=DeviceAttributeControllerTest,AttributeIntegrationTest test`
- Done: true

## Slice 3: Queue 管理 HTTP

- Goal: `/api/queues` CRUD 最小集 + `/api/queueStats` 读。
- Acceptance: AC-3
- Depends on: None
- Test or proof: `QueueAdminHttpIntegrationTest`
- Implementation: `QueueController` / `QueueStatsController`；`QUEUE_READ` 权限；写/删 SYS_ADMIN。
- Verification: `mvn -q -Dtest=QueueAdminHttpIntegrationTest test`
- Done: true

## Slice 4: Device / OTA / 平台回归

- Goal: type/profile 过滤、assign/unassign、非法 OTA 赋值、平台回归。
- Acceptance: AC-4, AC-5
- Depends on: Slice 1–3（可部分并行）
- Test or proof: `DeviceIntegrationTest` + Spec 所列平台回归集
- Implementation: `DeviceController`/`DeviceService`/`DeviceMapper`；OTA `requireAssignable`。
- Verification:

```bash
mvn -q -Dtest=DeviceIntegrationTest,TelemetryAdminHttpIntegrationTest,QueueAdminHttpIntegrationTest,DeviceAttributeControllerTest,IdentityAndUserLifecycleIntegrationTest,MfaIntegrationTest,OAuth2DexIntegrationTest,TenantCustomerIsolationIntegrationTest,SettingsApiKeyDomainIntegrationTest,AuditLogIntegrationTest,PasswordResetIntegrationTest,SecurityIntegrationTest test
```

- Done: true

## AC → Slice

| AC | Slice |
|----|-------|
| AC-1 | 1 |
| AC-2 | 2 |
| AC-3 | 3 |
| AC-4 | 4 |
| AC-5 | 4 |

## Build log

- 2026-08-19: Slice 1–4 落地 — `TelemetryAdminController`、Attribute 补齐、Queue/QueueStats HTTP、Device assign/过滤；配套 IT 通过。
- 2026-08-19: Spec 修订后同步 Plan：修正平台回归类名（`IdentityAndUserLifecycleIntegrationTest` 等）；验收命令与 Spec AC-5 对齐。
- 2026-08-19: Close-out — 补 `deviceProfileId` 过滤断言、Attribute 批量读 Controller 测试；Verification 命令 **exit 0**：

```bash
mvn -q -Dtest=DeviceIntegrationTest,TelemetryAdminHttpIntegrationTest,QueueAdminHttpIntegrationTest,DeviceAttributeControllerTest,IdentityAndUserLifecycleIntegrationTest,MfaIntegrationTest,OAuth2DexIntegrationTest,TenantCustomerIsolationIntegrationTest,SettingsApiKeyDomainIntegrationTest,AuditLogIntegrationTest,PasswordResetIntegrationTest,SecurityIntegrationTest test
```
