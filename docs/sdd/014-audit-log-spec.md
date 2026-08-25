# 审计日志行为说明

## 设计原则

- **尽力而为（best effort）**：落库、SpEL、JSON 解析等失败只记 warn 与指标，**不阻断**业务 HTTP/登录/删除等操作。
- **事务后写入**：Service 内 `publishEvent(AuditEvent)` 在业务事务 **提交后** 落库；回滚时不产生记录。
- **声明式 `@Audited`**：适用于 Controller 同步路径；**不要**标在 `@Transactional` Service 方法上（会在提交前写入，回滚后出现幽灵记录）。

## MFA / 登录审计状态机

| 阶段 | 是否记 `LOGIN_SUCCESS` | 记什么 |
|------|------------------------|--------|
| 密码正确，需配置 MFA | 否 | 无（仅返回 configuration token） |
| 密码正确，需验证 MFA | 否 | 无（仅返回 pre-verification token） |
| MFA 验证码错误 | — | `MFA_FAILED` |
| MFA 验证通过 | — | `MFA_VERIFIED` |
| MFA 会话完成（`/login/mfa/session`） | 是 | `@Audited LOGIN_SUCCESS` |
| 无 MFA 的完整登录 | 是 | `LOGIN_SUCCESS`（Handler 事件） |

运维排查「用户是否完整登录」：无 MFA 看 `LOGIN_SUCCESS`；有 MFA 看 `MFA_VERIFIED` + `LOGIN_SUCCESS`（session 端点）。

## 异步 RPC

管理端 `POST /api/rpc/oneway|twoway` **不用** `@Audited`（避免仅 `ACCEPTED`）。在 `DeferredResult` 完成时发布 `DEVICE_RPC_SENT`，状态为 `SUCCEEDED` 或 `FAILED`。

## 上下文来源

| `origin` | 来源 |
|----------|------|
| `HTTP` | `ServletAuditContextProvider`（uri、method、remoteAddr） |
| `MQTT` | 设备 publish 处理线程 `AuditContextHolder` |
| `SCHEDULED` | 保留清理等定时任务 |
| `INTERNAL` | 无 Web/显式上下文（如异步事件监听线程） |

跨线程发布 `AuditEvent` 时应显式携带 `AuditContext`。框架入口为 `AuditTemplate`（声明式 `@Audited` / 编程式 `record`）；持久化通过 `AuditWriter`。

## 配置

```yaml
roseboard.audit.enabled: true          # false 时审计模块不加载；HTTP RequestId 仍由 web 装配
server.forward-headers-strategy: none  # 反向代理后改为 framework 或 native，审计 remoteAddr 随 Spring 解析
```

HTTP 请求的 `requestId` 来自 `X-Request-Id`（`infrastructure.web.RequestIdFilter`）；查询：`GET /api/audit-logs?requestId=...`

## 指标

- `roseboard.audit.writes{status,action}`
- `roseboard.audit.record.failed{status,action}`
- `roseboard.audit.retention.deleted`
