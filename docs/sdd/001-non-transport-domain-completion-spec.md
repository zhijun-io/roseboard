# Roseboard Non-Transport Domain Completion Spec

> **状态：已验收通过。** 交付顺序 `001`。Plan：[`001-non-transport-domain-completion-plan.md`](001-non-transport-domain-completion-plan.md)。

## Goal

完善 **非传输层**管理面：管理端遥测 HTTP、Attribute 管理端补齐、Queue 管理 HTTP，以及 Device 列表过滤 / assign customer、OTA 赋值校验，并以 checklist 场景一～六对应的仓库集成测试作回归门禁。**不含** Transport Core / HTTP `/api/http` 设备面实施。

## Dependencies

- Requires: 既有 Device / Attribute Service / Telemetry Service / Queue 配置与 Stats / OTA / 平台 Auth 等。
- Provides: 非传输管理面 HTTP 与回归门禁（交付顺序 001）。
- Followed by: [`002`](002-tenant-profile-usage-spec.md)（Profile/Usage）；[`003`](003-transport-core-spec.md) / [`004`](004-transport-http-device-api-spec.md)（传输 Core 与 HTTP）；[`005`](005-transport-mqtt-device-api-spec.md)（MQTT Adapter）；[`006`](006-cluster-runtime-spec.md) → [`007`](007-cache-consistency-spec.md) → [`008`](008-notification-center-spec.md) → [`009`](009-alarm-event-spec.md) → [`010`](010-gateway-subdevices-spec.md) → [`011`](011-websocket-api-spec.md) → [`012`](012-websocket-api-phase2-spec.md) → [`013`](013-queue-runtime-advanced-spec.md)。
- Does not own: Transport Core、设备 `/api/http`、MQTT 等 Adapter、RE/Actor、Alarm/Asset/Edge/Dashboard、Gateway、缓存一致性、Tenant Usage 计量全量（见 002）。

## Scope

**做（本 Spec 契约内）：**

| 域 | 契约交付 |
|----|----------|
| Telemetry | 管理端 HTTP，**仅 DEVICE**：keys、latest、history（无聚合）、写 timeseries、按 keys/时间范围删；路径前缀 `/api/plugins/telemetry`；**不是** `/api/http` |
| Attribute | `/api/devices/{id}/attributes`：按 scope 全量或 keys 批量读；`/attributes/keys`；删（`expectedVersion` 可选，缺省用当前版本）；既有批量 POST 保留 |
| Queue | `/api/queues`：分页/按 id/按 name 读；SYS_ADMIN 创建或更新、删除；`/api/queueStats` 最小读（分页、按 id、list） |
| Device | 列表 `type`、`deviceProfileId` 过滤；`PUT /api/devices/{deviceId}/customer` assign；`DELETE /api/devices/{deviceId}/customer` unassign；普通 `POST /api/devicess` **不得**偷改 customer/tenant |
| Credentials | 保持既有 generate / masked GET / revoke 边界（轮换=重新 generate；撤销后旧 token 失效）；本 Spec **不**扩多类型凭证管理 HTTP |
| Device Profile | 本 Spec **不**新增 Profile HTTP；设备保存时校验 profile 归属（既有） |
| OTA | 设备 `firmwareId`/`softwareId` 赋值须通过 `requireAssignable`（租户、类型、已发布）；非法 id → **4xx**（常见 404）；既有 OTA 包管理 IT 不回归 |
| 平台 | 跑通下方「回归测试集」；失败则修；禁止引入 `infrastructure.transport` 实施包、RE、Alarm 等 |

**不做：** Transport Core/HTTP 设备面、MQTT 等、RE/Actor、Alarm/Asset/Event/Edge/Dashboard、Gateway、缓存 Spec、Tenant Profile/Usage 全量（002）、遥测聚合（`agg`/`interval`）、完整 TB `TelemetryController` 非 DEVICE 实体。

## Current Context（实现后）

- 交付序：见 [`CONTEXT.md`](../../CONTEXT.md)（`001`…`013`）。
- 清单：[`thingsboard-scenario-migration-checklist.md`](../design/thingsboard-scenario-migration-checklist.md)。
- Telemetry：`TelemetryAdminController` → `TelemetryService`。
- Attribute：`DeviceAttributeController`（GET 单 key / 批量读 / keys / POST 批量写 / DELETE）。
- Queue：`QueueController`、`QueueStatsController`；`PermissionService` 含 `QUEUE_READ`（TENANT_ADMIN）；写/删另需 SYS_ADMIN；写操作经 `QueueService.createManaged` / `updateManaged` / `deleteManaged`（内部调用 `QueueCoordinator`）与已注册 binding 联动重启 consumer。
- Device：`DeviceController` / `DeviceService` / `DeviceMapper` 支持过滤与 assign/unassign。

## TB Alignment

| 面 | TB | Roseboard（本 Spec） |
|----|----|---------------------|
| 管理端遥测 | `TelemetryController` DEVICE 子集 | `/api/plugins/telemetry/DEVICE/{id}/keys\|values/timeseries[...]`；写 `/timeseries/{scope}`；删 `/timeseries/delete` |
| Attribute | 管理端 attributes | `/api/devices/{id}/attributes*`（Roseboard scope 枚举 `CLIENT\|SHARED\|SERVER`，非 TB `*_SCOPE` 字符串） |
| Queue | `QueueController` / `QueueStatsController` 最小集 | `/api/queues*`、`/api/queueStats*`（无 TB `serviceType` 必填；写仅 SYS_ADMIN） |
| Device assign | `assignDeviceToCustomer` / unassign | 同路径语义 |
| 传输设备 API | `/api/http` | **不做**（003/004） |

## Requirements

1. DEVICE 管理端遥测：keys、latest、history、写、删；数据范围与 `DEVICE` 权限一致；跨租户拒绝。
2. Attribute：按 scope 全量或 keys 读、keys 列表、删除（显式或隐式版本）；越权拒绝。
3. Queue：租户可读本租户配置与 Stats；SYS_ADMIN 可写/删；读委托 `QueueService` / `QueueStatsRepository`；写/删委托 `QueueCoordinator` 托管生命周期（有 binding 时重启 consumer）。
4. Device：`type`/`deviceProfileId` 过滤；assign/unassign；save 路径禁止擅自改 customer/tenant；非法 OTA 包 id 赋值失败（**4xx**，通常 404 NOT_FOUND）。
5. Credentials：generate 返回一次性明文、masked GET 不回显密钥、revoke 后鉴权失败（既有 `DeviceIntegrationTest` 覆盖即可）。
6. 平台回归测试集全部通过；本 Spec diff 不新增传输 Core 包、不引入 RE/Alarm 实现。

## Acceptance Criteria

- AC-1: When 租户管理员对本租户 DEVICE 调用管理端遥测 keys/latest/history/写/删, then HTTP 成功且与 `TelemetryService` 一致；When 跨租户, then 拒绝。
- AC-2: When 管理端按 scope 批量读 attributes、列 keys、删除 attribute, then 与 Store/Service 一致；越权拒绝。
- AC-3: When 调用 `/api/queues` 与 `/api/queueStats` 最小 API, then 读与配置/Stats 仓库一致；无权限或非 SYS_ADMIN 写则拒绝。
- AC-4: When 列表带 `type`/`deviceProfileId`、assign/unassign customer、非法 OTA id 赋值, then 过滤/归属/校验符合上述契约。
- AC-5: When 跑「本 Spec 新增 IT + 平台回归测试集」, then 全部通过；无新 `com.roseboard.infrastructure.transport` 实施交付、无 RE/Alarm 领域落地。

### 本 Spec 新增 / 扩展 IT

- `TelemetryAdminHttpIntegrationTest`
- `QueueAdminHttpIntegrationTest`
- `DeviceAttributeControllerTest`（含 keys/删）
- `DeviceIntegrationTest`（含 assign/过滤/非法 OTA）

### 平台回归测试集（AC-5；以仓库类名为准）

| 优先级 | 测试类 | 对应 checklist |
|--------|--------|----------------|
| P0 | `IdentityAndUserLifecycleIntegrationTest` | 场景一（清单旧名 `AuthenticationLifecycleIntegrationTest`） |
| P0 | `MfaIntegrationTest` | 场景二 |
| P0 | `OAuth2DexIntegrationTest` | 场景三 |
| P1 | `TenantCustomerIsolationIntegrationTest` | 场景四 |
| P1 | `SettingsApiKeyDomainIntegrationTest` | 场景五 |
| P1 | `AuditLogIntegrationTest` | 场景六 |
| P1 | `PasswordResetIntegrationTest` | 场景一密码重置 |
| P1 | `SecurityIntegrationTest` | 安全基线（仓库已有） |

> 清单中的 `OAuth2FailureIntegrationTest` / `ThingsBoardCompatibilityIntegrationTest` 若仓库不存在，**不阻塞**本 Spec AC-5；补测另开任务。

## Out of Scope

- Transport Core；HTTP `/api/http`；MQTT/CoAP/LwM2M；集群；缓存 Spec；Alarm/Asset/Edge/Dashboard/Gateway；Tenant Usage 全量；遥测聚合与非 DEVICE 实体遥测。

## Revision log

- 2026-08-19 | 初版 Spec + Plan 切片 | AC-1..5
- 2026-08-19 | 实现落地（见 Plan Build log） | AC-1..5
- 2026-08-19 | 更新 Spec：修正 Current Context；钉死遥测子集/凭证边界/回归类名；状态改为已实现待验收 | clarification — plan 同步验证命令
- 2026-08-19 | 验收通过：补齐 `deviceProfileId` 过滤与 Attribute 批量读测试；Verification 全绿 | AC-1..5
