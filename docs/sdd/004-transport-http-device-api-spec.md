# Roseboard Transport HTTP Device API Spec

> **状态：进行中。** Slice 1 已验收（2026-08-19）：遥测 / CLIENT 属性 / GET attributes。Slice 2–4 未交付。  
> Plan：[`004-transport-http-device-api-plan.md`](004-transport-http-device-api-plan.md)（含实现映射与交付状态）

## Goal

在 **已落地的 Transport Core**（[`003`](003-transport-core-spec.md)）之上，完成 ThingsBoard **HTTP 设备面**（`/api/http`）迁移：遥测/属性、双向 RPC、claim、provision、OTA 下载、属性长订阅；并接通管理端 `/api/rpc` 在线投递。路径与对外契约对齐 TB `DeviceApiController` / `RpcV2Controller`。

## Dependencies

- Requires: **已验收** [`003`](003-transport-core-spec.md)；Device / Attribute / Telemetry / RPC / Profile / OTA；Security 已 permit `/api/http/**`。
- Provides: HTTP 设备面 `/api/http`（Adapter 包见 Plan）。
- Does not own: Transport Core 内核（003）、MQTT/CoAP/LwM2M Adapter、Rule Engine、Queue Provider、OTA 管理端 CRUD。

## TB 基线（本地源码）

与 [`roseboard-thingsboard-queue-migration.md`](../design/roseboard-thingsboard-queue-migration.md) 相同 commit：`684f92bbfd`（仓库 sibling：`../thingsboard/`）。

| 对照文件 | 路径（相对 roseboard 根） |
|----------|---------------------------|
| 设备 HTTP | `../thingsboard/common/transport/http/src/main/java/org/thingsboard/server/transport/http/DeviceApiController.java` |
| 管理端 RPC | `../thingsboard/application/src/main/java/org/thingsboard/server/controller/RpcV2Controller.java` |

## Scope（TB 路径表）

**设备面 = TB `DeviceApiController` 11 路由（无增删）。** 另含管理端 `RpcV2Controller` 的 oneway/twoway（RPC 闭环所需，不在 DeviceApiController 内）。

| Method | Path | TB 语义 | Slice |
|--------|------|---------|-------|
| POST | `/api/http/{deviceToken}/telemetry` | 上报遥测（JSON） | 1 |
| POST | `/api/http/{deviceToken}/attributes` | 上报 CLIENT 属性 | 1 |
| GET | `/api/http/{deviceToken}/attributes` | 读 client/shared keys（`clientKeys` / `sharedKeys`） | 1 |
| GET | `/api/http/{deviceToken}/attributes/updates` | shared 变更长轮询（`timeout`） | 3 |
| GET | `/api/http/{deviceToken}/rpc` | 服务端→设备 RPC 长轮询（`timeout`；整型 id） | 2 |
| POST | `/api/http/{deviceToken}/rpc/{requestId}` | 设备回复服务端 RPC（整型 id） | 2 |
| POST | `/api/http/{deviceToken}/rpc` | 设备→云 RPC（等服务端 JSON 回复） | 3 |
| POST | `/api/http/{deviceToken}/claim` | 设备 claiming | 4 |
| POST | `/api/http/provision` | 设备 provisioning（无 path token） | 4 |
| GET | `/api/http/{deviceToken}/firmware` | OTA 固件（`title` / `version`；可选 `chunk` / `size`） | 4 |
| GET | `/api/http/{deviceToken}/software` | OTA 软件（同上） | 4 |
| POST | `/api/rpc/oneway/{deviceId}` | 管理端单向 RPC | 2 |
| POST | `/api/rpc/twoway/{deviceId}` | 管理端双向 RPC | 2 |

**契约约束：**

- 请求/响应体 **默认 JSON**（对齐 TB HTTP）。
- HTTP 路径凭证：仅 **ACCESS_TOKEN**（path `deviceToken`）。
- 设备**写**路径（telemetry / attributes POST）不得 Adapter 直写领域；须经 Transport Core 入队（003）。
- GET attributes **直查领域**（对齐 TB，不经上行 Queue）。
- 管理端非持久 RPC、设备未在线等待 → **504**；持久 RPC 落库并由设备 `GET .../rpc` 拉取。

## Non-goals

- 实现或修改 Transport Core 内核（003）。
- MQTT/CoAP/LwM2M Adapter；Gateway 子设备（[`010`](010-gateway-subdevices-spec.md)）。
- HTTP 上非 TB 多凭证头；HTTP Protobuf body。
- inbox/outbox；新领域表/Entity；Rule Engine。

## TB Alignment（HTTP）

| 面 | TB | Roseboard |
|----|----|-----------|
| 设备路径与参数 | 上表 11 路由 | Slice 1 三路由已对齐；其余 ⏸ |
| 认证 | path Access Token | **相同** |
| 上行写 | Transport → Queue → 领域 | **相同**（003 Main） |
| GET attributes | 直查 + 长轮询响应 | **相同** |
| RPC | 整型 requestId；双向/设备→云 | 目标一致；⏸ |
| attributes/updates | shared 长轮询 | 目标一致；⏸ |
| 管理端非持久离线 | 504 | 非持久当前 504；在线投递 ⏸ |

## Requirements

1. 设备写路径：path token 鉴权；失败 **401**。
2. telemetry / CLIENT attributes POST：200 时领域可查（经 Queue，003）。
3. GET attributes：按 key 过滤；响应无 secret/凭证字段。
4. attributes/updates：shared 长轮询；超时行为固定可测。
5. 服务端→设备 RPC：整型 requestId；非持久且无等待会话 → 504；twoway 闭环。
6. 设备→云 RPC：响应体为服务端回复 JSON。
7. claim / provision / OTA：路径与 query/body 参数对齐 TB。
8. MockMvc（或同级）集成测试覆盖 AC；RPC 闭环须真实 HTTP，禁止仅 mock Core。

## Acceptance Criteria

- AC-1: When `POST /api/http/{validToken}/telemetry` with valid JSON, then 200 and Telemetry 可查（经 Queue）。
- AC-2: When `POST /api/http/{invalidToken}/telemetry`, then 401 and 无写入。
- AC-3: When `POST /api/http/{validToken}/attributes` with JSON, then CLIENT 属性可回读（经 Queue）。
- AC-4: When `GET .../attributes?clientKeys=&sharedKeys=`, then 仅含请求 key，无 secret/凭证字段。
- AC-5: When 管理端 twoway `persistent=false` 且设备未等待, then 504。
- AC-6: When 设备 `GET .../rpc` 等待中 + 管理端 twoway, then 设备收到含**整型 id**/method/params；`POST .../rpc/{id}` 后管理端收到同一回复 JSON。
- AC-7: When `persistent=true` 后设备 `GET .../rpc`, then 含整型 id/method/params；回复后行 `SUCCESSFUL` 且 response 匹配。
- AC-8: When 交付 RPC 闭环, then MockMvc IT 覆盖 AC-5/6/7；禁止仅单元 mock 代替。
- AC-9: When `POST .../claim`, then 200 且 SERVER 侧可观察 claiming；坏 token → 401。
- AC-10: When `POST /api/http/provision` with valid key/secret, then 200 且返回凭证；非法 → 4xx。
- AC-11: When `GET .../firmware?title=&version=` 匹配已分配包, then 200 字节（可选分片）；不匹配 → 4xx。
- AC-12: When `GET .../software` 同 AC-11 语义, then 行为对称。
- AC-13: When `GET .../attributes/updates` 等待中且 **shared** 变更, then 返回变更 JSON；超时行为固定并测。
- AC-14: When `POST .../rpc`（设备→云）with method/params, then 响应体为服务端回复 JSON；缺字段 → 400。
- AC-15: When 实现本 Spec, then 无新业务 Flyway 表、无新 `device.*` Entity、无 inbox/outbox。

## Constraints

- 对外 HTTP 契约对齐 TB；默认 JSON。
- 依赖 [`003`](003-transport-core-spec.md) AC 已满足。
- 实现类与包布局见 Plan，本 Spec 不钉死。

## Decisions

- Chosen: HTTP 为 Transport 上 **第一个** Adapter。
- Chosen: 整型 requestId；attributes/updates = **shared**（TB proto 同）。
- Chosen: GET attributes 直查领域（不经 Main Queue）。
- Rejected: Adapter 内直写领域（上行写路径）；本 Spec 实现其它协议 Adapter。

## Open Questions

（无）

## Revision log

- 2026-08-19 | 从合并 Spec **拆出** HTTP Device API | AC-1..15 | 原合并文档
- 2026-08-19 | 重编号为 `004-transport-http-device-api-spec.md` | none — clarification | plan 同步
- 2026-08-20 | 对齐本地 TB 基线与 Slice 1 实现状态 | Scope / TB 基线 | plan 同步
- 2026-08-20 | Spec 收敛为 TB 路径/AC；实现类名下沉 Plan | Scope 表 / Constraints | plan: 实现映射
