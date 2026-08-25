# Roseboard Alarm & Event Spec（大纲）

> **状态：stub。** 不授权实施。  
> 编号：`009`。  
> **原则：不完整迁移 TB 告警/事件那套繁重链路**（尤其依赖 Rule Engine / Actor / TbMsg 的部分）；借鉴生命周期与管理面形状，**重新实现**为 Roseboard 领域模型。

## Goal

提供可用的 **设备（及必要实体）告警** 与 **审计式/业务事件** 能力：创建、清除、确认、查询；事件可查询与关联实体——满足运营闭环，而非复刻 TB 全功能告警引擎。

## Dependencies

- Requires: Device 身份与数据范围；建议 [`001`](001-non-transport-domain-completion-spec.md) 管理面已可用；可选 Attribute/Telemetry 作为条件输入（后期）。
- Provides: `alarm` / `event`（或等价包）领域 + 管理 HTTP；告警产生后可调用 [`008`](008-notification-center-spec.md) 投递，不自建 Inbox。
- Does not require: Rule Engine、Actor、TbMsg。
- Does not own: Notification 投递与 Inbox（见 008）。

## Scope（待细化）

**Alarm（建议最小集）**

- 类型、严重级别、状态（ACTIVE / CLEARED / ACK 等 TB 常用子集）。
- 管理端：查询、确认、清除、删；按设备/租户过滤。
- 产生方式 Phase-1：**管理 API / 服务显式调用**；Phase-2 再考虑「阈值阈值」等简单规则（仍无 RE）。

**Event（建议最小集）**

- 实体生命周期与安全相关事件的可查询日志（与既有 Audit 边界划清：Audit=谁做了什么；Event=实体发生了什么）。
- 或：若与 Audit 重叠过大，本 Spec 可裁成「仅 Alarm」，Event 并入 Audit——**展开 Spec 时二选一写死**。

## Design stance

| TB | Roseboard |
|----|-----------|
| RE 创建告警、传播、通知链 | **不做 RE**；服务直接写 Alarm |
| 复杂 Alarm Comment / 分配 / 传播图 | 先不做或极简 |
| Event 多类型海量表 | 先窄类型枚举 + 分页查询 |

## Acceptance Criteria（占位）

- AC-stub: When Spec 批准后, then AC 覆盖告警状态机、权限隔离、无 RE 依赖。

## Out of Scope

- Notification 投递与 Inbox（[`008`](008-notification-center-spec.md)）；Dashboard；Edge 同步告警；RE 条件告警。
- Asset 作为一等告警主体（可后置；先 DEVICE）。
