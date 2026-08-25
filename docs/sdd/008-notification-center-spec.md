# Roseboard Notification Center Spec

> **状态：已批准。** 授权按 Plan 实施。  
> Plan：[`008-notification-center-plan.md`](008-notification-center-plan.md)

## Goal

**唯一投递入口** `NotificationCenter.notify`；文案进 **DB 模板**；业务域 **禁止** 直调 `MailService`/`SmsService`。

## 简化原则（相对前版）

| 删除 / 推迟 | 保留 |
|-------------|------|
| 独立 `notification.core` 拆库（Phase-1/2 **同仓单模块**） | 三表 + `notify` + 用户 Inbox |
| `DispatchService`、`TemplatePolicy` enum、`GET /scenarios` | `templateKey` 即场景；`auth.*` 规则在 `TemplateService` |
| `RecipientResolver` SPI | 调用方传入 `recipients`；Usage 自行解析 TENANT_ADMIN |
| `TemplateEngineRegistry` / AC-32 插件引擎 | **`SIMPLE`**（全局 `${key}`）+ **`FREEMARKER`**（仅系统 Auth seed，适配层一种实现） |
| `UsageMeteringCallback` SPI | `NotifyOptions.countUsage`（boolean）；`NotificationCenter` 实现内调 Usage |
| 五层 mermaid、Design Review 长文、重复 API 节 | 下文一份契约 |

## Architecture

**两层**（非三层）：

```
业务域 ──► NotificationCenter（com.roseboard.infrastructure.notification）
              ├── NotificationTemplateSource / NotificationChannelConfigSource（com.roseboard.notification.* 读 DB）
              ├── NotificationTemplateRenderer（SIMPLE + Freemarker）
              └── 编排 NotificationChannelSender（infrastructure.notification.spi）
com.roseboard.notification
              ├── TenantNotificationController / PlatformNotificationController / InboxController
              ├── NotificationService（Inbox 读写）
              └── channel / template / catalog
              （Email/SMS Sender 内部用 SmtpMailClient / SmsClient，**不**再暴露 MailService/SmsService 给编排层）
```

**数据（Phase-1/2）：**

| 表 | 作用 |
|----|------|
| `notification_template` | 文案 + `render_engine` + `delivery_methods` jsonb |
| `notification_channel_config` | 平台 SMTP/SMS；租户 **enabled**（Phase-2，INHERIT） |
| `notification` | 用户 Inbox（WEB 渠道落库；`recipient_id` = 当前用户） |

**模板策略（无 enum、无 binding 表）：**

- `templateKey` 以 **`auth.`** 开头 → 系统模板，租户 **不可** PUT；`NotificationTemplateSource` 强制 `(null, key)`。
- 其它 key（如 **`usage.threshold`**）→ 租户可 `(tenantId, templateKey)` 覆盖。
- **永久不做** `notification_scenario_binding`。

**投递求交：**

```
NotificationChannelConfigSource.resolve(tenantId, kind) 可用
  ∧ deliveryMethods[kind].enabled
  ∧ command.channels ∋ kind
  （ignoreTenantChannelDisabled 时跳过租户 enabled 开关，不跳过连接解析）
```

## NotificationCenter API

```java
public interface NotificationCenter {
    NotifyResult notify(UUID tenantId, NotifyCommand command);
    ConnectivityResult verifyConnectivity(UUID tenantId, ChannelKind kind);
}
```

```java
public record NotifyCommand(
    Set<ChannelKind> channels,              // 必填
    List<RecipientRef> recipients,          // 必填（调用方解析，含 Usage→TENANT_ADMIN）
    String type,
    String templateKey,
    Map<String, Object> vars,
    NotifyOptions options
) {}

public record NotifyOptions(
    boolean ignoreTenantChannelDisabled,    // Auth/MFA：跳过租户 channel enabled
    boolean countUsage                      // false=不计 maxEmails/maxSms（系统/阈值默认 false）
) {}
```

**默认值：** 系统触发（`auth.*`、`usage.threshold`）→ `countUsage=false`；Phase-3 运营手动发送 → `countUsage=true`（002 AC-14）。

**结果：** 每渠道独立 `DeliveryOutcome`；部分成功不回滚（AC-5）。

**连通性：** `verifyConnectivity` 委托 `NotificationChannelSender`；替代 `testMail`/`testSms`（Phase-2 删旧 HTTP）。

## 内部契约（最小 SPI）

| SPI | 实现位置 | 说明 |
|-----|----------|------|
| **`NotificationChannelSender`** | `infrastructure.notification.spi` | 按 `ChannelKind` 投递已渲染内容 + `verifyConnectivity`；**唯一**可触达 SmtpMailClient/SmsClient/NotificationService 的编排 SPI |
| `NotificationChannelConfigResolver` | `notification.channel` | `resolve` → `ResolvedChannelConfig`（含 `enabled`、`isComplete()`） |
| `NotificationTemplateSource` | `notification.template` | tenant 行 → fallback 系统行 |
| `NotificationTemplateRenderer` | `notification.template` | 按 `render_engine` 选 SIMPLE 或 Freemarker |
| `NotificationInboxWriter` | `notification`（`NotificationService`） | WEB 渠道写 Inbox；读模型仍由 `NotificationService` 提供 |

**`NotificationChannelSender`**（`infrastructure.notification.spi` 包）：基础设施**唯一**投递 SPI；与 `notification_channel_config` / `ChannelKind` 同域。

```java
public interface NotificationChannelSender {
    ChannelKind kind();
    DeliveryOutcome send(ChannelSendRequest request);
    ConnectivityResult verifyConnectivity(ResolvedChannelConfig config);
}

public record ChannelSendRequest(
    UUID tenantId,
    String notificationType,
    List<RecipientRef> recipients,
    RenderedChannelContent rendered,
    ResolvedChannelConfig channelConfig,
    boolean countUsage
) {}
```

| 实现类 | 内部依赖 |
|--------|----------|
| `EmailNotificationSender` | `SmtpMailClient` + `ResolvedChannelConfig`（SMTP/OAuth） |
| `SmsNotificationSender` | `SmsClient` |
| `WebNotificationSender` | `NotificationInboxWriter`（`NotificationService` 实现） |

- Spring 注入 `List<NotificationChannelSender>`，`NotificationCenter` 按 `kind()` 索引。
- Phase-1 验收后：**删除** `MailService`/`SmsService` 语义方法；`SmtpMailClient`/`SmsClient` 仅由对应 `*NotificationChannelSender` 调用。
- **禁止**新增 `DeliveryChannel` 或第二套投递 SPI。

**无** `RecipientResolver`：**Usage** 在 `TenantUsageThresholdListener` 查 TENANT_ADMIN 后组 `recipients`。

**Phase-1 过渡：** `EmailNotificationSender` 可先委托 `MailService.sendEmail(MailMessage)`（仅传输）；Slice 1 结束时 Auth/MFA **不再**注入 `MailService` 语义方法。Slice 2 起 SMTP 配置来自 `NotificationChannelConfigResolver`，Sender 直读 `ResolvedChannelConfig`。

**编排（伪代码）：**

```
template = templateSource.find(tenantId, key) // auth.* 强制系统行
rendered = templateRenderer.render(template, vars)
for kind in command.channels:
  cfg = channelConfigSource.resolve(tenantId, kind)
  if 不可用 → 该渠道失败/跳过
  channelSenders.get(kind).send(new ChannelSendRequest(tenantId, recipients, rendered.forKind(kind), cfg))
  if kind==EMAIL|SMS && command.options.countUsage → usageService.increment(...)
```

## 模板

- 系统 seed（Flyway）：`auth.*`（EMAIL，`FREEMARKER`）、`auth.mfa-sms`（SMS，`SIMPLE`）、`usage.threshold`（WEB+EMAIL，`SIMPLE`）。
- 租户 BUSINESS 模板：**仅** `render_engine=SIMPLE`。
- 禁止运行时读 `classpath:mail/*.ftl`（AC-30）。
- 渲染失败：`templateKey` 缺失 → 整单失败；单渠道语法错 → 该渠道 FAILED，其它不回滚。

## 渠道配置

**`notification_channel_config`：** `tenant_id` null=平台；`connection_mode` INHERIT|CUSTOM（租户 Phase-2 仅 INHERIT + enabled；CUSTOM 延 Phase-3）。

Phase-1：`NotificationChannelConfigResolver` 从 **`notification_channel_config`** 读取平台级 mail、sms 配置；不再从 `admin_settings` 保存通知渠道配置。

## HTTP

**Inbox（Phase-1）：** `NotificationController` `/api/notifications` — GET 分页（`unreadOnly`）、POST `{id}/read`、POST `read-all`。按 **当前 userId**；**drop** `tenant_notification`，不迁移历史。持久化表名 **`notification`**（非已删的 `notification_record`、过渡 `tenant_notification`）。

**管理（Phase-2）：**

| 路径 | 角色 |
|------|------|
| `GET/PUT /api/notifications/platform/channels/{kind}` | SYS_ADMIN |
| `POST …/platform/channels/{kind}/verify` | SYS_ADMIN |
| `GET/PUT /api/notifications/channels/{kind}` | TENANT_ADMIN（Phase-2：enabled；Phase-3：+ CUSTOM SMTP） |
| `POST …/channels/{kind}/verify` | TENANT_ADMIN |
| `GET/PUT /api/notifications/templates/{templateKey}` | TENANT_ADMIN（`auth.*`→403） |
| `GET/PUT /api/notifications/templates/system/{templateKey}` | SYS_ADMIN |
| `GET/POST/PUT/DELETE /api/notifications/targets` | TENANT_ADMIN（Phase-3） |
| `POST /api/notifications/deliveries` | TENANT_ADMIN（Phase-3 手动/批量发送） |

OAuth：行为同现 `MailOAuth2CompatibilityController`，配置源改 channel_config，路径迁入 `/platform/channels/EMAIL/oauth2/*`。

**权限：** Inbox — 现有 `NOTIFICATION_READ`（TENANT_ADMIN、CUSTOMER_USER）；模板/渠道 — `NOTIFICATION_WRITE` 或 `ADMIN_SETTINGS`（与现 Settings 一致，Plan 实施时二选一对齐代码）。

## Scope

### Phase-1

- V60：`notification_template` + **`notification`** + seed；drop `tenant_notification`
- `NotificationCenter` + WEB/EMAIL + Inbox + SIMPLE/Freemarker 渲染
- Auth / Usage / MFA → `notify`；`countUsage=false`
- 邮件仍读 `admin_settings`（Legacy `NotificationChannelConfigResolver`）

### Phase-2

- V61：`notification_channel_config` + admin_settings 迁出
- 租户模板 CRUD、`/channels`、SMS、`verifyConnectivity` HTTP
- 删 testMail/testSms

### Phase-3（不验收）

- 租户 CUSTOM SMTP、`notification_target`、手动 `POST …/send`、WS `DeliveryListener`、飞书/钉钉

## Non-goals

- TB 整仓迁移；RE/Actor；独立 Maven `notification-core` artifact（**推迟**）；scenario_binding；Inbox 历史迁移。

## Acceptance Criteria

### Phase-1（明细）

- AC-1: `notify` + WEB + 多位 TENANT_ADMIN → 每人一条 UNREAD（`notification` 表，含 scopeId/recipientId/type/subject/body）。
- AC-2: 同租户 `userIds` 各一条；跨租户 recipient → 400/403。
- AC-3: GET `/api/notifications` 仅本人；`unreadOnly`；他人 id mark-read → 404/403。
- AC-4: 本人 UNREAD → READ。
- AC-5: WEB+EMAIL、N 收件人 → N 条 Inbox + N 次邮件；Email 失败不回滚 WEB。
- AC-6: Usage WARNING/EXCEEDED → 仅 `notify(usage.threshold)`；listener 内 dedupe；自行组 recipients。
- AC-7/36: 阈值/Auth/MFA 成功 → `maxEmails`/`maxSms` 不增（`countUsage=false`）。
- AC-8: `tenant_notification` 已 drop；Inbox 仅 `notification` 表。
- AC-9: CUSTOMER_USER 可读本人 Inbox。
- AC-10: 业务包无 `MailService`/`SmsService` import；仅 `infrastructure.notification..` 可实现 `NotificationChannelSender`。
- AC-11: 注册测试用 `NotificationChannelSender` 可被 `notify` 选中，无需改 Usage。
- AC-19: `NotificationCenter` 实现不 import `com.roseboard.user`/`tenant`/`setting` 业务域。
- AC-20: Phase-1 测试集全绿。
- AC-27: Auth/MFA 经 `notify` + `auth.*` templateKey。
- AC-29/30: 系统模板 seed 含 FREEMARKER Auth；无 runtime `.ftl`。
- AC-31: `auth.activation` 渲染 body 含 `${link}` HTML。

### Phase-2（明细）

- AC-12..17: `/channels`、租户模板覆盖、仅 WEB、SMS、mark-all-read。
- AC-21: Phase-2 测试集全绿。
- AC-22..25: 平台渠道 PUT/verify、admin_settings 迁移、平台 disabled 阻断 INHERIT 租户。
- AC-26: MFA 经 `notify` 单渠道。
- AC-28: testMail/testSms 已删。
- AC-35: 租户 PUT `auth.*` → 403；`usage.threshold` → 200。

### Phase-3

- AC-18、AC-33、AC-34

### 测试集

| Phase | 类 |
|-------|-----|
| 1 | `NotificationCenterTest`、`NotificationHttpIntegrationTest`、`NotificationTemplateSeedTest`、`TenantUsageRuntimeHttpIntegrationTest` |
| 2 | `NotificationCatalogHttpIntegrationTest`、`ChannelConfigMigrationTest`、`MailOAuth2CompatibilityIntegrationTest` |

```bash
# Phase-1
mvn -q -Dtest=NotificationCenterTest,NotificationHttpIntegrationTest,TenantUsageRuntimeHttpIntegrationTest test
```

## 迁移清单

| 现代码 | 改为 |
|--------|------|
| `TenantUsageThresholdListener` | 查 TENANT_ADMIN → `notify(WEB,EMAIL, usage.threshold, countUsage=false)` |
| `AuthController` / MFA | `notify(EMAIL\|SMS, auth.*, ignoreTenantChannelDisabled=true, countUsage=false)` |
| `TenantNotificationController` | 删除 → `NotificationController` |

## Decisions

- Chosen: **两层** + **五 SPI**；调用方传 recipients；`templateKey` 即场景。
- Chosen: `countUsage` 替代 `usageMetering` enum + 独立 Callback。
- Rejected: `DispatchService`、`TemplatePolicy` enum、`RecipientResolver`、`TemplateEngineRegistry`、Phase-1/2 拆 core 库。
- Chosen: **`NotificationChannelSender`** 为基础设施唯一投递 SPI；`EmailNotificationSender` / `SmsNotificationSender` / `WebNotificationSender`；内部 `SmtpMailClient`/`SmsClient`。
- Rejected: `DeliveryChannel` 命名及与 `NotificationChannelSender` 并存；Rejected: 编排层依赖 `MailService`/`SmsService`。
- Chosen: Auth/MFA/usage 告警 `countUsage=false`（原 AC-7/36）。

## Revision log

- 2026-08-19 | **设计简化**：两层架构；删 Dispatch/TemplatePolicy/RecipientResolver/EngineRegistry/core 拆库；`countUsage` 替代 UsageMeteringCallback | Architecture | plan impact yes
- 2026-08-19 | Inbox 表名 `user_notification` → **`notification`**；Entity **`NotificationEntity`** | Data model | plan impact yes
- 2026-08-19 | **Spec 批准** — 授权 Plan 实施 | Status | plan impact n/a
- 2026-08-19 | 租户渠道 API 路径 `channel-settings` → **`/channels`**；包布局与实现对齐（契约在 `infrastructure.notification`，HTTP/DB 在 `notification`） | HTTP / Architecture | plan impact yes
