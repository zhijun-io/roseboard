# Notification Center Plan

**Spec:** [`008-notification-center-spec.md`](008-notification-center-spec.md)

> **状态：已批准，可实施。**  
> Spec：[`008-notification-center-spec.md`](008-notification-center-spec.md)（已批准，2026-08-19）  
> **范围：** 3 切片 + 三表；无 DispatchService、无 notification.core 独立模块。

## Slice 1：投递 + Inbox + 模板 seed（Phase-1 主体）

- **Goal:** `NotificationCenter`；**`notification`** 表；**`EmailNotificationSender` + `WebNotificationSender`**（Phase-1）；V60；Auth/Usage/MFA → `notify`。
- **Breaking:** 删 `TenantNotificationController` → `NotificationController`；drop `tenant_notification`。
- **AC:** 1–11、19–20、27、29–31、7、36
- **Flyway:** `V60__notification_inbox_and_template.sql`（附录 B.1）
- **Test:** `NotificationCenterTest`、`NotificationHttpIntegrationTest`、`NotificationTemplateSeedTest`、`TenantUsageRuntimeHttpIntegrationTest`

```bash
mvn -q -Dtest=NotificationCenterTest,NotificationHttpIntegrationTest,NotificationTemplateSeedTest,TenantUsageRuntimeHttpIntegrationTest test
```

## Slice 2：渠道表 + 平台配置 + SMS（Phase-2）

- **Goal:** V61 `notification_channel_config`；admin_settings 迁出；**`SmsNotificationSender`**；删 testMail/testSms；OAuth 路径迁入；平台模板/渠道 PUT 写 `audit_log`。
- **AC:** 22–28
- **Test:** `ChannelConfigMigrationTest`、`MailOAuth2CompatibilityIntegrationTest`

## Slice 3：租户模板 + 渠道配置（Phase-2）

- **Goal:** `TemplateService`（`auth.*`→403）；租户 `usage.threshold` CRUD；`/channels/{kind}`（enabled；Phase-3 扩展 CUSTOM SMTP）；**Usage 继续直调 notify**（不引入 DispatchService）。
- **AC:** 12–17、35
- **Fixture:** 附录 A.2–A.5
- **Test:** `NotificationCatalogHttpIntegrationTest`

```bash
mvn -q -Dtest=NotificationCatalogHttpIntegrationTest,ChannelConfigMigrationTest,TenantUsageRuntimeHttpIntegrationTest test
```

## Phase-3（Spec 可选）

CUSTOM SMTP、target 表、手动发送、WS。

## 实施钉死项

1. **`NotificationCenter` 一处编排**：渲染 + 渠道求交 + 调 **`NotificationChannelSender`**。
2. **Usage 收件人**：`TenantUsageThresholdListener` 内查 admin 用户，**不**建 RecipientResolver。
3. **系统模板规则**：`TemplateService.isSystemKey(key)` ⇔ `key.startsWith("auth.")`。
4. **计量**：`NotifyOptions.countUsage`；系统通知默认 `false`。
5. DDL / seed：附录 B、A 不变。
6. **投递 SPI：** 仅 **`NotificationChannelSender`**（`EmailNotificationSender` / `WebNotificationSender` / `SmsNotificationSender`）；包 `infrastructure.notification.spi`；**禁止** `DeliveryChannel`。

## AC → Slice

| AC | Slice |
|----|-------|
| 1–11、19–20、27、29–31、7、36 | 1 |
| 22–28 | 2 |
| 12–17、35 | 3 |
| 18、33、34 | Phase-3 |

---

## 附录 A：集成测试 Sample Seed

测试常量：

| 符号 | UUID |
|------|------|
| `TENANT_B` | `bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb` |
| `TENANT_C` | `cccccccc-cccc-cccc-cccc-cccccccccccc` |
| `TENANT_D` | `dddddddd-dddd-dddd-dddd-dddddddddddd` |
| `TS` | `1735689600000` |

### A.1 Phase-1 最小 seed

```sql
INSERT INTO notification_template (
    id, tenant_id, template_key, render_engine, notification_type, name,
    delivery_methods, created_time, updated_time
) VALUES
(
    gen_random_uuid(), NULL, 'auth.activation', 'FREEMARKER', 'AUTH', 'Account activation',
    '{"EMAIL":{"enabled":true,"subject":"Activate ${email}","body":"<p><a href=\"${link}\">Activate</a></p>","html":true}}'::jsonb,
    :ts, :ts
),
(
    gen_random_uuid(), NULL, 'usage.threshold', 'SIMPLE', 'USAGE_LIMIT', 'Usage threshold',
    '{"WEB":{"enabled":true,"subject":"Usage ${status}","body":"${metricKey} is ${status} for period ${period}."},
      "EMAIL":{"enabled":true,"subject":"Usage ${status}","body":"${metricKey} is ${status} for period ${period}.","html":false}}'::jsonb,
    :ts, :ts
);
```

### A.2 tenant-B：定制 usage 文案

```sql
INSERT INTO notification_template (
    id, tenant_id, template_key, render_engine, notification_type, name,
    delivery_methods, created_time, updated_time
) VALUES (
    gen_random_uuid(), :tenant_b, 'usage.threshold', 'SIMPLE', 'USAGE_LIMIT', 'Usage (tenant B)',
    '{"WEB":{"enabled":true,"subject":"【告警】${status}","body":"【重要】${metricKey} 已${status}，周期 ${period}。"},
      "EMAIL":{"enabled":true,"subject":"【告警】${status}","body":"【重要】${metricKey} 已${status}，周期 ${period}。","html":false}}'::jsonb,
    :ts, :ts
);
INSERT INTO notification_channel_config (tenant_id, channel_kind, enabled, connection_mode, config, secrets, created_time, updated_time)
VALUES
(:tenant_b, 'WEB', true, 'INHERIT', '{}'::jsonb, '{}'::jsonb, :ts, :ts),
(:tenant_b, 'EMAIL', true, 'INHERIT', '{}'::jsonb, '{}'::jsonb, :ts, :ts);
```

**断言：** notify + tenant-B admins → 文案含 `【重要】`。

### A.3 tenant-C：CUSTOM SMTP（Phase-3；IT 可直插行）

```sql
INSERT INTO notification_channel_config (tenant_id, channel_kind, enabled, connection_mode, config, secrets, created_time, updated_time)
VALUES (:tenant_c, 'EMAIL', true, 'CUSTOM',
  '{"smtpHost":"smtp.customer.test","smtpPort":587,"from":"alert@customer.test","tls":true}'::jsonb,
  '{"password":"customer-smtp-secret"}'::jsonb, :ts, :ts);
```

### A.4 tenant-D：EMAIL disabled

```sql
INSERT INTO notification_channel_config (tenant_id, channel_kind, enabled, connection_mode, config, secrets, created_time, updated_time)
VALUES
(:tenant_d, 'WEB', true, 'INHERIT', '{}'::jsonb, '{}'::jsonb, :ts, :ts),
(:tenant_d, 'EMAIL', false, 'INHERIT', '{}'::jsonb, '{}'::jsonb, :ts, :ts);
```

### A.5 auth.activation 负向（AC-35）

```sql
INSERT INTO notification_template (id, tenant_id, template_key, render_engine, notification_type, name, delivery_methods, created_time, updated_time)
VALUES (gen_random_uuid(), :tenant_b, 'auth.activation', 'SIMPLE', 'AUTH', 'Malicious',
  '{"EMAIL":{"enabled":true,"subject":"Hacked","body":"Hacked","html":false}}'::jsonb, :ts, :ts);
```

### A.6 关系速查

```
usage.threshold
  notification_template (null) ← 系统
  notification_template (tenant-B) ← 租户覆盖
  notification_channel_config ← resolve 渠道
  TenantUsageThresholdListener → 查 TENANT_ADMIN → notify
```

---

## 附录 B：Flyway DDL（V60 / V61）

> PostgreSQL 15+：`UNIQUE (tenant_id, …) NULLS NOT DISTINCT`。

### B.1 `V60__notification_inbox_and_template.sql`

```sql
create table if not exists notification_template (
    id uuid not null constraint notification_template_pkey primary key,
    created_time bigint not null,
    updated_time bigint not null,
    tenant_id uuid,
    template_key varchar(128) not null,
    render_engine varchar(32) not null default 'SIMPLE',
    notification_type varchar(64) not null,
    name varchar(255) not null,
    delivery_methods jsonb not null,
    constraint fk_notification_template_tenant
        foreign key (tenant_id) references tenant(id) on delete cascade
);

create unique index if not exists notification_template_tenant_key_uidx
    on notification_template (tenant_id, template_key) nulls not distinct;

create table if not exists notification (
    id uuid not null constraint notification_pkey primary key,
    created_time bigint not null,
    scope_id uuid not null,
    recipient_id uuid not null,
    type varchar(64) not null,
    subject varchar(512) not null,
    body text not null,
    status varchar(16) not null default 'UNREAD',
    read_time bigint,
    constraint fk_notification_scope foreign key (scope_id) references tenant(id) on delete cascade,
    constraint fk_notification_recipient foreign key (recipient_id) references tb_user(id) on delete cascade,
    constraint notification_status_chk check (status in ('UNREAD', 'READ'))
);

create index if not exists notification_recipient_created_idx
    on notification (recipient_id, created_time desc);

create index if not exists notification_recipient_status_idx
    on notification (recipient_id, status)
    where status = 'UNREAD';

-- 8 条系统 seed（auth.* + usage.threshold）；正文自 mail/*.ftl 迁入
-- …（与 Spec 系统 templateKey 清单一致，实施时补全）

drop table if exists tenant_notification;
```

### B.2 `V61__notification_channel_config.sql`

```sql
create table if not exists notification_channel_config (
    id uuid primary key default gen_random_uuid(),
    tenant_id uuid,
    channel_kind varchar(16) not null,
    enabled boolean not null default true,
    connection_mode varchar(16) not null default 'INHERIT',
    config jsonb not null default '{}'::jsonb,
    secrets jsonb not null default '{}'::jsonb,
    created_time bigint not null,
    updated_time bigint not null,
    constraint fk_notification_channel_config_tenant
        foreign key (tenant_id) references tenant(id) on delete cascade,
    constraint notification_channel_config_mode_chk
        check (connection_mode in ('INHERIT', 'CUSTOM'))
);

create unique index if not exists notification_channel_config_scope_kind_uidx
    on notification_channel_config (tenant_id, channel_kind) nulls not distinct;

-- 自 admin_settings mail/sms 迁出（SYSTEM_TENANT_ID = 00000000-0000-0000-0000-000000000000）
insert into notification_channel_config (tenant_id, channel_kind, enabled, connection_mode, config, secrets, created_time, updated_time)
select null, 'EMAIL', coalesce((s.json_value::jsonb ->> 'enabled')::boolean, true), 'CUSTOM',
  jsonb_strip_nulls(jsonb_build_object(
    'smtpHost', s.json_value::jsonb ->> 'smtpHost',
    'smtpPort', (s.json_value::jsonb ->> 'smtpPort')::int,
    'from', s.json_value::jsonb ->> 'from',
    'username', s.json_value::jsonb ->> 'username',
    'tls', coalesce((s.json_value::jsonb ->> 'tls')::boolean, true),
    'oauth2', s.json_value::jsonb -> 'oauth2')),
  jsonb_strip_nulls(jsonb_build_object(
    'password', s.json_value::jsonb ->> 'password',
    'refreshToken', s.json_value::jsonb -> 'oauth2' ->> 'refreshToken')),
  s.created_time, s.created_time
from admin_settings s
where s.key = 'mail' and s.tenant_id = '00000000-0000-0000-0000-000000000000'::uuid
  and not exists (select 1 from notification_channel_config c where c.tenant_id is null and c.channel_kind = 'EMAIL');

insert into notification_channel_config (tenant_id, channel_kind, enabled, connection_mode, config, secrets, created_time, updated_time)
select null, 'SMS', coalesce((s.json_value::jsonb ->> 'enabled')::boolean, false), 'CUSTOM',
  jsonb_strip_nulls(jsonb_build_object('provider', s.json_value::jsonb ->> 'provider', 'baseUrl', s.json_value::jsonb ->> 'baseUrl')),
  jsonb_strip_nulls(jsonb_build_object('apiKey', s.json_value::jsonb ->> 'apiKey')),
  s.created_time, s.created_time
from admin_settings s
where s.key = 'sms' and s.tenant_id = '00000000-0000-0000-0000-000000000000'::uuid
  and not exists (select 1 from notification_channel_config c where c.tenant_id is null and c.channel_kind = 'SMS');

delete from admin_settings where key in ('mail', 'sms') and tenant_id = '00000000-0000-0000-0000-000000000000'::uuid;
```

---

## Build log

- 2026-08-19: Plan 初稿 + 附录 A/B。
- 2026-08-19: **设计简化** — 3 切片；删 DispatchService / core 拆库 / RecipientResolver；Usage 直调 notify。
- 2026-08-19: **Spec 批准** — 可实施 Slice 1。
