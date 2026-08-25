# Roseboard Cache Consistency Spec

> **状态：已批准。**
> 编号：`007`。本规格按 ThingsBoard 的缓存分层、cache-aside、事务缓存填充和 eviction event 语义实现，按 Roseboard 的 PostgreSQL、Transport Core 与无 Actor 约束落地。

## Goal

为 Device、Device Profile、Device Credentials、Attribute、Telemetry latest 建立与 ThingsBoard 对齐的缓存策略：

- PostgreSQL 始终是唯一事实源；
- 单节点默认使用本地 Caffeine 缓存；
- 多节点可切换到 Redis/Valkey 共享缓存；
- cache miss、缓存禁用或缓存未命中时回源 PostgreSQL；
- 缓存只优化读取，缓存后端故障不得成为数据库事实写入的一部分；
- 鉴权、租户隔离和凭证撤销不能被缓存脏数据绕过。

缓存只优化读取，不承载业务事实，也不改变现有 HTTP、MQTT、WebSocket 或 Transport API 契约。

## TB Alignment

本规格对齐 ThingsBoard 可观察缓存语义，而不是复制 ThingsBoard 的 DAO 或 Actor 实现：

| ThingsBoard | Roseboard |
|---|---|
| 默认 Caffeine，Redis 可选 | 默认 Caffeine，Redis/Valkey 可选 |
| `deviceCredentials`、`devices`、`deviceProfiles` cache spec | 相同领域缓存 |
| `attributes`、`tsLatest` cache spec | 相同 latest 读模型缓存 |
| cache-aside 读路径 | 相同 |
| 实体写入触发 eviction event | 领域写事务提交后发布等价 eviction event |
| Credentials 可缓存空查询结果 | 允许缓存“未找到”标记，写入/轮换/撤销时必须失效 |
| Device versioned cache | 使用实体版本或等价失效栅栏，禁止旧值覆盖新值 |

不迁移 ThingsBoard 的 Device Actor、TbMsg、Rule Engine 或 DAO 层。

## Dependencies

- `DeviceService`、`DeviceProfileService`、`DeviceCredentialService`
- `DeviceAttributeService`
- `TelemetryService`
- 现有 Redis/Valkey 连接配置
- [`006-cluster-runtime-spec.md`](006-cluster-runtime-spec.md) 的多节点运行语义
- Spring Cache/Caffeine 或等价本地缓存实现
- 现有 `ApplicationEventPublisher` 领域事件机制

## Transaction Boundary

缓存失效必须服从领域数据库事务，而不是与数据库写入并行执行。

当前 Device、Device Profile、Device Credentials、Attribute 和 Telemetry 写路径已经使用 Spring `@Transactional`。本规格要求缓存失效事件绑定到这些事务：

1. 领域服务在事务内写入 PostgreSQL；
2. 领域服务在事务内注册 eviction event；
3. PostgreSQL 事务成功提交；
4. `AFTER_COMMIT` listener 执行本地/共享缓存失效；
5. Redis/Valkey 后端删除共享 key，Caffeine 后端删除当前 JVM key；
6. 事务回滚时，eviction listener 不得执行本次事件。

实现必须使用 `AFTER_COMMIT` 语义或等价的事务同步机制。普通同步 `ApplicationEventPublisher.publishEvent(...)` 只是注册事务事件，不能单独作为缓存失效执行保证。

没有活动数据库事务时，按 ThingsBoard 语义直接执行 eviction。缓存清理失败不得回滚已经提交的领域事务；TTL 和后续读取回源负责最终修复。


### TB Transaction Semantics

ThingsBoard 的“事务缓存”包含两个不同层次，不能误解为数据库与缓存之间的两阶段提交：

1. **缓存填充事务**：cache miss 后读取数据库，使用按 key 的 cache transaction 写入缓存；数据库读取或缓存写入失败时回滚该 cache transaction。
2. **数据库写入后的失效事务语义**：数据库事务内发布 eviction event；`@TransactionalEventListener` 在事务提交后执行失效。无数据库事务时，直接执行失效。

Roseboard 按相同语义实现：

- cache miss 的数据库读取与缓存回填使用等价的按 key 原子操作；
- 缓存回填不与 PostgreSQL 组成两阶段提交；
- PostgreSQL 提交失败不能留下本次写入的缓存值；
- PostgreSQL 提交成功后，缓存失效事件必须执行；
- 缓存失效失败不能回滚已经提交的 PostgreSQL 事务，后续读取必须回源并由 TTL 兜底。


## Scope

### 缓存对象

1. Device 元数据
2. Device Profile 元数据
3. Device Credentials 按 credentialsId 的鉴权查询结果
4. Attribute 当前值
5. Telemetry latest 当前值

### 缓存后端

支持两种后端：

| 类型 | 用途 |
|---|---|
| `caffeine` | 单节点默认；缓存只存在当前 JVM |
| `redis` | 多节点共享缓存值，并传播跨节点失效 |

默认后端为 `caffeine`。缓存禁用或 cache miss 时直接访问 PostgreSQL；缓存后端异常按后端实现的基础设施错误处理，不得返回伪造或未经验证的缓存结果，且不得回滚已提交的领域事务。

### 不做

- 把缓存作为事实源；
- 分布式事务；
- 分布式锁；
- 历史 Telemetry 查询缓存；
- Transport Session 缓存；
- Queue 内部缓冲；
- 新增缓存业务表；
- Device Actor、Rule Engine、TbMsg；
- 修改现有 API 请求/响应格式；
- 为缓存建立第二套业务数据库。

## Cache Configuration

每类缓存必须具备：

- `enabled`
- `timeToLive`
- `maxSize`

默认配置与 ThingsBoard 当前缓存 spec 对齐：

| 缓存 | enabled | TTL | maxSize |
|---|---:|---:|---:|
| `deviceCredentials` | true | 1440 分钟 | 10000 |
| `devices` | true | 1440 分钟 | 10000 |
| `deviceProfiles` | true | 1440 分钟 | 10000 |
| `attributes` | true | 1440 分钟 | 100000 |
| `tsLatest` | true | 1440 分钟 | 100000 |

`maxSize=0` 禁用该缓存。禁用或 miss 时直接访问 PostgreSQL。

实现可以使用 Roseboard 自己的配置前缀，但必须保留上述五类缓存、默认值和启停语义。不得把短 TTL 作为主动失效的替代方案。

## Cache Keys

缓存键必须使用版本化命名空间，并为租户隔离的领域实体包含租户边界：

```text
roseboard:cache:v1:
```

推荐键形状：

```text
device:{tenantId}:{deviceId}
device-profile:{tenantId}:{profileId}
credentials-auth:{credentialsType}:{lookupKey}
attribute:{tenantId}:{deviceId}:{scope}:{key}
ts-latest:{tenantId}:{deviceId}:{key}
```

其中：

- `lookupKey` 按 ThingsBoard Credentials lookup 语义使用 credentialsId 或等价查找标识；
- Access Token、password、LwM2M key 或证书内容不得作为可读 key 暴露；实现可以对 lookupKey 做内部编码；
- Credentials lookup key 是全局唯一凭证标识，不能在认证前添加 tenantId；缓存值必须携带并校验 Principal 的 tenantId；
- 同一个设备不同 Attribute scope 必须使用不同 key；
- 不同租户不能命中同一 Device、Profile、Attribute 或 Telemetry 缓存值。

## Cache Values

### Device / Device Profile

缓存完整的安全领域读模型，不能缓存绕过租户检查所需的权限结论。租户/客户范围仍由现有领域服务校验。

### Credentials

允许缓存：

- 正向认证 Principal；
- “按 credentialsId 未找到”的空结果标记。

禁止缓存：

- Access Token 明文；
- MQTT password；
- LwM2M key；
- X.509 证书内容；
- `credentialsValue` 原文。

正向和负向结果都必须受 `deviceCredentials` TTL 和 `maxSize` 约束。

### Attribute / Telemetry latest

缓存值必须保留领域需要的设备、租户、scope、key、时间戳和实际值。缓存只用于 latest 读路径，不改变历史 Telemetry 持久化和查询行为。

## Read Semantics

所有缓存读路径采用 cache-aside：

1. 读取缓存；
2. 命中且未过期时返回缓存值；
3. miss、禁用、解析失败或缓存后端异常时查询 PostgreSQL；
4. 数据库查询成功后回填缓存；
5. 数据库查询失败时返回原有领域错误，不伪造缓存结果。

并发 miss 可以有重复回源，但不得返回跨租户、跨设备或已删除对象。

Device 及 Profile 的版本字段必须参与旧值保护：较旧的异步回填不能覆盖较新的缓存值。

## Write and Eviction Semantics

PostgreSQL 写事务成功提交后，发布对应的 eviction event。事务回滚不得发布失效事件。

### Device

以下操作必须失效 Device key：

- 创建；
- 修改；
- 修改 customerId；
- 修改 deviceProfileId；
- 删除。

删除设备时还必须失效其 Credentials、Attribute 和 Telemetry latest 相关 key。

### Device Profile

以下操作必须失效 Profile key：

- 创建；
- 修改；
- 删除；
- 设备切换 Profile。

### Device Credentials

以下操作必须失效认证缓存：

- 保存；
- 替换；
- Access Token 轮换；
- 撤销；
- 禁用；
- 删除设备。

Credentials 更新必须同时失效旧 credentialsId、旧 lookupHash、新 credentialsId 和新 lookupHash。该要求适用于正向和负向缓存标记。

### Attribute

以下操作必须失效对应 `{tenantId, deviceId, scope, key}`：

- 单条写入；
- 批量写入；
- 删除；
- 设备删除。

### Telemetry

以下操作必须失效对应 `{tenantId, deviceId, key}` 的 `tsLatest`：

- 单点写入；
- 批量写入；
- latest 删除；
- 设备删除。

历史 Telemetry retention 删除不需要扫描或重建全部 latest cache；如果删除范围包含某个 latest 值，必须使该 key 失效。

## Eviction Events

Roseboard 采用 ThingsBoard 等价的进程内 typed eviction event：

- Device、Profile、Credentials、Attribute、Telemetry latest 分别使用对应的领域事件类型；
- 事件至少包含受影响的 cache kind、tenant/device/profile identity、scope/key，以及需要时的旧/新 credentialsId 和 entity version；
- 事件在数据库事务内注册；
- `@TransactionalEventListener` 只在事务提交后执行 eviction；
- 没有活动数据库事务时，直接执行 eviction；
- Caffeine 后端删除当前 JVM key；
- Redis 后端删除共享 cache key，其他节点无需额外订阅即可看到 key 已失效；
- eviction 必须幂等，重复调用不能破坏缓存；
- eviction 失败不能回滚已经提交的数据库事务，TTL 和 cache miss 回源负责最终修复；
- 本规格不要求 Redis Pub/Sub、持久化 eviction 队列或第二套事件总线。

## Consistency Model

本规格整体是**最终一致性缓存**，不是 PostgreSQL 与缓存之间的强一致性或两阶段提交：

- PostgreSQL 事务提供数据库事实的事务一致性；
- cache fill transaction 只保证单个缓存 key 的填充操作提交或回滚；
- 单节点 eviction listener 完成后，后续无并发 in-flight fill 的读取应回源或得到新值；
- 多节点依赖 Redis 共享 key eviction，存在 listener 执行前、并发读取和故障恢复窗口；
- 不承诺跨节点线性一致性，也不承诺任何并发读取都立即观察到最新值；
- TTL、cache miss 和数据库回源负责最终收敛。

## Consistency Guarantees

### 单节点

同一进程中，领域写事务提交且 eviction listener 完成后，下一次没有并发 in-flight fill 的读取不能返回写入前的旧缓存值。

### 多节点

节点 A 提交写事务后，`AFTER_COMMIT` listener 删除 Redis 共享 cache key；节点 B 下一次读取必须 cache miss、回源 PostgreSQL 并得到新值。

Redis eviction 执行失败时，节点 B 允许在 TTL 窗口内暂时命中旧值；缓存 miss 或 TTL 到期后必须回源 PostgreSQL。

并发读取已经在 eviction 前取得旧值时，该次读取可以完成旧结果；本规格不提供跨节点线性一致性。

### Credentials

Credentials 使用比普通读模型更严格的失效语义，但仍不是跨节点线性一致性：

- 当前节点写入、轮换或撤销后，`AFTER_COMMIT` listener 立即失效；
- Redis 模式下其他节点读取共享 cache key 的删除结果；
- 旧凭证不能在失效处理完成后继续认证；
- 新凭证可以正常认证；
- 无效凭证查询可以缓存，但写入、轮换、撤销时必须清除对应负缓存。

## Acceptance Criteria

### AC-1：TB 对齐的缓存配置

系统支持 `caffeine` 和 `redis` 两种缓存后端，默认使用 `caffeine`，并提供 `deviceCredentials`、`devices`、`deviceProfiles`、`attributes`、`tsLatest` 五类缓存的 `enabled`、TTL 和 `maxSize` 配置。

默认配置为：五类缓存 TTL 均为 `1440` 分钟；`deviceCredentials`、`devices`、`deviceProfiles` 的 `maxSize` 为 `10000`；`attributes`、`tsLatest` 的 `maxSize` 为 `100000`。`maxSize=0` 禁用对应缓存。

### AC-2：Device/Profile cache-aside

Device 和 Device Profile 读取命中缓存时返回等价领域读模型；cache miss 或禁用时回源 PostgreSQL 并可回填缓存。缓存后端异常不得返回未经验证的数据。

删除或更新后，当前节点和已处理 eviction event 的其他节点不能继续返回旧对象。

### AC-3：Credentials 正负缓存与失效

Credentials 鉴权同时支持正向结果缓存和未找到结果缓存，但不得缓存明文凭证。

保存、替换、轮换、撤销、禁用或删除设备后，旧 credentialsId 和新 credentialsId 相关缓存都必须失效。失效完成后旧凭证不能通过缓存继续认证。

### AC-4：Attribute 和 Telemetry latest

Attribute 与 Telemetry latest 读取采用 cache-aside。

写入、批量写入、删除和设备删除后，对应缓存 key 必须失效；CLIENT、SHARED、SERVER、不同设备、不同租户和不同 Telemetry key 之间不得串值。

历史 Telemetry 查询结果不受本缓存实现影响。

### AC-5：事务后 eviction event

数据库事务内注册 eviction event；事务提交后 listener 执行失效；事务回滚不执行 listener。

重复执行 eviction 必须幂等，不能把旧值覆盖到新值，也不能导致缓存组件退出。

### AC-6：跨节点失效

两个使用 Redis/Valkey 共享缓存的应用节点之间：

1. 节点 A 在事务内修改领域数据并注册 eviction event；
2. 节点 A 提交数据库事务；
3. 节点 A 的 `AFTER_COMMIT` listener 删除共享缓存 key；
4. 节点 B 下一次读取 cache miss，回源 PostgreSQL 并得到新值。

### AC-7：缓存后端故障与回源

缓存被禁用或 cache miss 时，Device、Profile、Credentials、Attribute 和 Telemetry latest 读取都回源 PostgreSQL。缓存后端异常不得返回伪造或未经验证的数据，也不得回滚已经提交的领域写事务；具体异常处理遵循选定缓存后端的基础设施策略。

### AC-8：租户隔离与敏感数据保护

Device、Profile、Attribute 和 Telemetry 缓存键包含租户边界；Credentials lookup key 按 ThingsBoard 语义使用全局唯一 credentialsId，缓存值必须携带并校验 Principal 的 tenantId。不同租户不能命中相同领域缓存值；缓存 key/value 中不得出现明文 Access Token、password、LwM2M key 或证书内容。

### AC-9：回归


缓存实现不改变现有 HTTP、MQTT、WebSocket、Credentials、Attribute、Telemetry 和 Transport 行为；现有完整 Maven 测试通过，且不新增业务缓存表。

## Out of Scope

- Transport Session 内存表（属 [`003`](003-transport-core-spec.md)）；
- Queue 内部缓冲；
- Device Actor、Rule Engine、TbMsg；
- 历史 Telemetry 查询缓存；
- 分布式事务与分布式锁；
- 缓存作为事实源。

## Revision Log

| 日期 | 原因 | AC 变化 | Plan 影响 |
|---|---|---|---|
| 2026-08-21 | 将 stub 细化为 TB 对齐的可实施契约 | 新增 `AC-1` 至 `AC-9` | 是；需要新建并批准 `007-cache-consistency-plan.md` |
| 2026-08-21 | 用户确认按 ThingsBoard 实现对齐 | none — 实现方向确认 | 是；进入 `sdd-plan` |
