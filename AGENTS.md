# AGENTS.md

## Context

Roseboard 是 Java 21、Spring Boot、MyBatis-Plus、PostgreSQL、Flyway 和 Redis/Valkey 构成的 IoT 设备接入与租户运营平台。前端位于 `ui/`，使用 React、TypeScript 和 Vite。

按任务读取以下上下文：

- 产品范围、启动方式或部署相关改动：读 [`README.md`](README.md)。
- 领域术语、包边界、Transport 或交付阶段相关改动：读 [`CONTEXT.md`](CONTEXT.md)，并以当前源码和对应 Spec 为准处理文档漂移。
- 任何源码、测试或提交改动：遵循 [`CONTRIBUTING.md`](CONTRIBUTING.md)。
- 某个功能存在 Spec 或 Plan：修改前读 [`docs/sdd/`](docs/sdd/) 下对应文件；验收标准和约束以该文件为准。

## Structure

| 路径 | 用途 |
|---|---|
| `src/main/java/com/roseboard/` | 后端源码；按领域包和 `infrastructure` 适配层组织 |
| `src/main/resources/application.yml` | Spring 默认配置 |
| `src/main/resources/db/migration/` | Flyway schema 迁移 |
| `src/test/java/com/roseboard/` | 单元测试、集成测试和测试 fixture |
| `ui/` | React/Vite 前端；命令以 `ui/package.json` 为准 |
| `docs/sdd/` | 功能 Spec、Plan 和验收记录 |
| `scripts/` | 设备、MQTT 和 WebSocket 生命周期验证脚本 |
| `docker/`, `compose*.yaml` | 本地依赖与容器编排 |
| `plans/` | 审查产生的短期执行计划，不是产品行为的长期来源 |

后端保持既有依赖方向：

```text
Controller / Adapter → Service → Mapper / Repository
```

Controller 负责 HTTP 或协议映射、鉴权、输入边界和响应状态。业务规则、作用域判断、事务和跨服务编排放在 Service。Mapper 只负责持久化。

协议无关能力放在 `infrastructure.transport`；缓存框架放在 `infrastructure.cache`（入口 `CacheTemplate`，存储 `cache.store`，失效 `cache.eviction`）；审计框架放在 `infrastructure.audit`（入口 `AuditTemplate`，写入 `audit.writer`，上下文 `audit.context`）；队列框架放在 `infrastructure.queue`（入口 `QueueCoordinator`，SPI `queue.spi`，值对象 `queue.model`、适配器 `queue.adapter`、运行时按职责分包）；HTTP 相关装配（含 `X-Request-Id`）放在 `infrastructure.web`；HTTP、MQTT 等协议细节放在对应 adapter 子包。领域服务不依赖协议请求、topic 或 wire payload。

数据库结构只通过 `src/main/resources/db/migration/` 中新的 Flyway 迁移修改。保持版本单调递增；不要改写已经共享的历史迁移。

## Commands

从仓库根目录运行后端命令。

### Backend

启动应用：

```bash
mvn spring-boot:run
```

主验证命令：

```bash
mvn verify
```

较窄的验证也可以：

```bash
mvn test
mvn -Dtest=<TestClass> test
mvn -Dit.test=<IntegrationTestClass> verify
```

仅检查主源码和测试源码能否编译：

```bash
mvn -DskipTests test
```

构建 Boot JAR：

```bash
mvn -DskipTests package
```

启动 Compose 前先生成 JAR：

```bash
mvn -DskipTests package
docker compose up --build
```

### Frontend

从 `ui/` 运行：

```bash
npm ci
npm run build
npm run dev
```

只做 TypeScript 检查时可以运行：

```bash
./node_modules/.bin/tsc --noEmit -p tsconfig.app.json
```

`ui/package.json` 当前没有 `test` 或 `lint` script。不要声称运行了不存在的前端门禁。

### Behavioral smoke tests

应用及其依赖启动后，可按改动范围运行：

```bash
./scripts/device-lifecycle.sh
./scripts/mqtt-device-lifecycle.sh
./scripts/websocket-lifecycle.sh
```

这些脚本需要相应的环境变量和本地工具；具体输入读 [`README.md`](README.md)。

## Change discipline

先确认当前工作树状态。保留用户已有的 staged、unstaged 和 untracked 修改；让每个修改行直接对应当前任务。

优先复用现有 Service、模型和框架工具。一次性逻辑保持直接；只有多个真实调用方共享稳定契约时才提取抽象。

修改公开方法、Controller 路径、DTO、Mapper 或配置键时，更新所有调用方和对应测试。默认做干净切换：删除被替代的实现、旧路径和过期注释；兼容层只有在 Spec 明确要求时保留。

测试按行为边界选择：

- 纯逻辑使用单元测试。
- Mapper、数据库、HTTP 或跨组件行为使用集成测试。
- 只有需要完整应用上下文时才使用 `@SpringBootTest`。
- 测试名写清输入场景和可观察结果。

新增或修改集成测试前，先检查 `src/test/java/com/roseboard/support/IntegrationTestBase.java` 及相邻测试，复用容器、属性注册和 fixture 模式，避免复制第二套测试基础设施。

## Source conventions

- Java 使用 import；源码中不写全限定类名。日志或错误文本中的类型名不受此限制。
- 使用既有 ORM、Wrapper 和 Mapper API；复杂 SQL 放在持久层 XML，不散落在 Controller 或 Service。
- 使用 Spring、Apache Commons 和现有项目工具处理字符串、集合、空值及 JSON，避免重复私有 helper。
- 租户、客户、用户和设备的归属字段是授权边界。更新实体时先加载旧值，在 Service 中验证并保持不可变字段。
- 设备凭证、JWT、API key、OAuth client secret 和环境变量属于敏感数据。不要提交、记录或在列表响应中暴露这些值。
- 配置示例使用占位符；真实值放在未跟踪的环境配置中。

## Generated files

保持以下内容不进入提交：

```text
target/
.idea/
.vscode/
ui/node_modules/
ui/dist/
*.tsbuildinfo
.env
.env.*
```

`.env.example` 可以提交，但只能包含变量名和无敏感性的示例值。

格式化器、编译器或构建产生额外文件时，只保留任务明确要求且仓库约定跟踪的产物。

## Commit and PR

每个提交只包含一个逻辑变化。新提交使用中文：

```text
<type>: <描述>
```

允许的 `type`：

```text
feat
fix
docs
refactor
chore
merge
```

标题写清具体结果，例如：

```text
fix: 禁止用户更新时切换租户归属
refactor: 将 OAuth2 持久化移入服务层
```

历史提交不改写；上述规范仅适用于新提交。

PR 或交付说明包含：

- 为什么修改；
- 关联 Issue、Spec 或 Plan；
- 主要行为变化；
- 实际运行的验证命令及结果；
- 已知风险或未覆盖范围。

## Agent guardrails

- 读取相关实现、调用方和测试后再修改。
- 发现工作树中的并行修改时，基于当前文件继续工作，不覆盖或回退他人改动。
- 编译失败时先恢复绿色基线，再进行结构重构。
- 生产行为、文档和测试发生冲突时停止扩展范围，指出冲突并以明确的 Spec 或用户决定解决。
- 不执行 `git push`、破坏性 reset、clean、强制 checkout 或删除未知文件。
- 不创建兼容别名、弃用包装或双路径实现来掩盖未完成迁移。
