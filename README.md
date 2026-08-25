# Roseboard

Roseboard 是基于 Spring Boot 4.1、MyBatis-Plus 3.5.17 和 PostgreSQL 18 的 ThingsBoard 认证与授权能力裁剪重实现。

## 当前范围

保留：

- 用户、租户、客户
- 用户名/邮箱密码认证
- JWT Access Token 和 Refresh Token
- OAuth2/OIDC Client Login
- EMAIL、TOTP、SMS MFA/2FA
- 系统安全、JWT、邮件、OAuth2 和 MFA 设置
- Spring Security 注解式授权
- 租户/客户数据隔离
- 审计日志

不实现：

- Cassandra
- 设备、遥测、告警、规则链等 IoT 能力
- 租户套餐
- 通用 API 限流
- ThingsBoard Angular 前端直接运行兼容

完整规划见 [`docs/sdd/`](docs/sdd/)（`001`…`013`，编号即建议交付序）。

API 路径命名规则见 [`docs/api-naming-conventions.md`](docs/api-naming-conventions.md)。

贡献约定见 [`CONTRIBUTING.md`](CONTRIBUTING.md)。


## 技术栈

- Java 21
- Spring Boot 4.1
- Spring Security
- MyBatis-Plus 3.5.17
- PostgreSQL 18
- Flyway
- Redis/Valkey
- Maven
- Docker Compose

## 本地启动

直接运行：

```bash
mvn spring-boot:run
```

构建分层镜像前，先在项目根目录生成 Boot JAR：

```bash
mvn -DskipTests package
docker compose up --build
```

Dockerfile 只负责提取 Spring Boot 分层 JAR 和运行应用，不执行 Maven 构建。

服务地址：

- Roseboard：<http://localhost:8080>
- PostgreSQL：`localhost:5432`
- Valkey：`localhost:6379`
- Mailpit：<http://localhost:8025>

## 设备生命周期演示

服务启动后，可以用 Bash 脚本演示设备创建、凭证生成、Client 属性上报、遥测上报、设备到云 RPC、云到设备双向 RPC，以及可选删除：

```bash
ROSEBOARD_ADMIN_TOKEN=<JWT> \
ROSEBOARD_TENANT_ID=<TENANT_UUID> \
./scripts/device-lifecycle.sh
```

如果没有现成 JWT，也可以使用管理员登录信息：

```bash
ROSEBOARD_ADMIN_EMAIL=<EMAIL> \
ROSEBOARD_ADMIN_PASSWORD=<PASSWORD> \
ROSEBOARD_TENANT_ID=<TENANT_UUID> \
./scripts/device-lifecycle.sh
```

脚本默认保留演示设备，使用 `--cleanup` 在退出时删除：

```bash
ROSEBOARD_ADMIN_TOKEN=<JWT> \
ROSEBOARD_TENANT_ID=<TENANT_UUID> \
./scripts/device-lifecycle.sh --cleanup
```

脚本依赖 `curl` 和 `python3`，默认访问 `http://localhost:8080`；可通过 `ROSEBOARD_BASE_URL` 修改。

启用 MQTT 监听后，可运行纯 MQTT 协议生命周期验证脚本。脚本覆盖认证、心跳、订阅/取消订阅、QoS 0/1/2、属性、遥测、设备 RPC、云端 RPC、Claim、固件/软件分片下载、Provision 和非法主题拒绝：

```bash
ROSEBOARD_MQTT_ENABLED=true \
ROSEBOARD_ADMIN_EMAIL=<EMAIL> \
ROSEBOARD_ADMIN_PASSWORD=<PASSWORD> \
ROSEBOARD_TENANT_ID=<TENANT_UUID> \
./scripts/mqtt-device-lifecycle.sh --cleanup
```

脚本默认访问 `http://localhost:8080` 和 MQTT `127.0.0.1:1883`；可通过 `ROSEBOARD_BASE_URL`、`ROSEBOARD_MQTT_HOST`、`ROSEBOARD_MQTT_PORT` 修改。需要 `curl`、`jq`、`nc` 和 `python3`。

MQTT 设备主题约定：

- `devices/me/telemetry`、`devices/me/attributes`
- `devices/me/attributes/request/{requestId}` 与 `devices/me/attributes/response/{requestId}`
- `devices/me/rpc/request/{requestId}` 与 `devices/me/rpc/response/{requestId}`
- `devices/me/claim`
- `devices/me/firmware/{request|response}/{requestId}/chunk/{chunk}`
- `devices/me/software/{request|response}/{requestId}/chunk/{chunk}`
- Provision 使用独立的 `provision/request` 与 `provision/response`

管理 WebSocket 接口可使用生命周期脚本验证。脚本覆盖 JWT/API key 认证、非法认证、`/api/ws`、Telemetry/Notifications 插件端点，以及 TIMESERIES、ATTRIBUTES、历史查询、ENTITY_DATA、ENTITY_COUNT、通知查询/计数、标记已读和全部取消订阅请求：

```bash
ROSEBOARD_ADMIN_EMAIL=<EMAIL> \
ROSEBOARD_ADMIN_PASSWORD=<PASSWORD> \
ROSEBOARD_TENANT_ID=<TENANT_UUID> \
./scripts/websocket-lifecycle.sh --cleanup
```

脚本默认访问 `http://localhost:8080` 和 `ws://localhost:8080`；可通过 `ROSEBOARD_BASE_URL`、`ROSEBOARD_WS_BASE_URL` 修改。需要 `curl`、`jq`、`node` 和 `python3`。Node.js 24 的内置 WebSocket API 用于客户端连接。

当前仓库仅包含项目基础骨架和 Flyway bootstrap migration；业务模块将按规划规格分阶段实现。
