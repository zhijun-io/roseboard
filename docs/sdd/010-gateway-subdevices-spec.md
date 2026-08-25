# Roseboard Gateway Sub-devices Spec（大纲）

> **状态：stub。** **建议单独开 Spec（即本文件）。** 不授权实施。  
> 编号：`010`。依赖传输面，排在 [`003`](003-transport-core-spec.md) / [`004`](004-transport-http-device-api-spec.md) / [`005`](005-transport-mqtt-device-api-spec.md) 之后。

## Goal

支持 **网关设备代理子设备**：网关鉴权一次，代报子设备遥测/属性/连接状态，管理面可区分网关与子设备关系——借鉴 TB Gateway API 语义，**重新实现**为 Core 上的清晰模型，不迁 TB Gateway MQTT 话题动物园的全部历史包袱。

## Dependencies

- Requires: Device + Credentials；Transport Core 会话；至少一种 Adapter（典型 MQTT；HTTP 网关可选）。
- Provides: 网关↔子设备关系、上行封装解析、子设备会话/活动可选语义。
- Default（见 CONTEXT）：**无**独立 gateway token 表；关系落在 Device/`additionalInfo` 或显式轻量表（Spec 展开时论证）。

## Scope（待细化）

- 标记网关设备（Profile 类型或 flag）。
- 子设备注册/绑定/解绑（管理 API + 网关侧 connect 消息）。
- 上行：网关 payload → 多个 `deviceName` 的 Telemetry/Attribute 扇出（经 Queue → Service）。
- 下行：按子设备路由到网关会话（依赖 Core `deliver` + 网关协议封装）。
- **不做**：Edge 同步；Gateway 专用 Rule Chain；TB 全部 legacy topic 别名（只保留当前主路径）。

## Design stance

| 问题 | 建议 |
|------|------|
| 是否开 Spec？ | **要开**，且独立于 003/004，避免 Core/HTTP Spec 膨胀 |
| 是否先于 MQTT？ | 可先定领域关系 + HTTP 管理面；协议扇出放 Adapter 切片 |
| 子设备凭证？ | 默认子设备仍是一等 Device（可有自己的 credentials）；网关仅代理，不共享网关 token 给子设备 |

## Acceptance Criteria（占位）

- AC-stub: When Spec 批准后, then AC 覆盖绑定、代报上行、越权（他租户子设备）、断连后不可投递。

## Out of Scope

- 通用 MQTT Adapter 全量（另 Spec）；Alarm 因网关产生（可调用 [`009`](009-alarm-event-spec.md) API，不在本 Spec 实现引擎）。
