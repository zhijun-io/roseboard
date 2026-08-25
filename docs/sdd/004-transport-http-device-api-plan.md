# Transport HTTP Device API Implementation Plan

**Spec:** [`004-transport-http-device-api-spec.md`](004-transport-http-device-api-spec.md)  
**Depends on:** [`003-transport-core-spec.md`](003-transport-core-spec.md) / [`003-transport-core-plan.md`](003-transport-core-plan.md)  
**TB 基线：** 本地 `../thingsboard/`，commit `684f92bbfd`（`DeviceApiController` / `RpcV2Controller`）

> **状态：进行中。** Slice 1–3 已验收；Slice 4 待 Build。

## 路径交付状态

| Path | Slice | 状态 | 备注 |
|------|-------|------|------|
| `POST .../telemetry` | 1 | ✅ | |
| `POST .../attributes` | 1 | ✅ | |
| `GET .../attributes` | 1 | ✅ | |
| `GET .../attributes/updates` | 3 | ✅ | shared 长轮询 + 超时 408 |
| `GET/POST .../rpc*` | 2–3 | ✅ | Slice 2：GET + POST reply；Slice 3：POST 设备→云 |
| `POST .../claim` / `POST /api/http/provision` | 4 | ✅ | |
| `GET .../firmware` / `.../software` | 4 | ⏸ | |
| `POST /api/rpc/oneway\|twoway/{deviceId}` | 2 | ✅ | 非持久需设备 Session 否则 504；持久落库；在线 direct deliver |

### AC 状态

| AC | 状态 | 测试 |
|----|------|------|
| AC-1..4 | ✅ | `HttpDeviceTelemetryAttributeIntegrationTest` |
| AC-5..8 | ✅ | `HttpDeviceRpcIntegrationTest` |
| AC-13, AC-14 | ✅ | `HttpDeviceSlice3IntegrationTest` |
| AC-9..12, AC-15 | ⏸ | — |

## 实现映射（Roseboard）

| 区域 | 类 / 组件 |
|------|-----------|
| HTTP Adapter | `com.roseboard.infrastructure.transport.http.DeviceApiController` |
| 鉴权回调 | `DeviceAuthCallback`（controller 内部类） |
| GET attributes 响应 | `HttpSessionListener.onGetAttributesResponse` |
| Transport SPI | `TransportService` / `DefaultTransportService` |
| Session / 下行 | `TransportSessionRegistry` |
| RPC 编排 | `TransportRpcService` |
| Queue 装配 | `TransportQueueRuntime`（`TransportConfiguration`） |
| Main consumer | `TransportQueueRuntime.handleMain`（`telemetry.post` / `attributes.post`） |
| 下行 consumer | `TransportQueueRuntime.handleDownlink` → `TransportNotifications` |
| 管理端 RPC | `com.roseboard.device.rpc.DeviceRpcController` → `TransportRpcService` |

**Slice 1 数据流：**

```text
POST telemetry|attributes
  → DeviceApiController → TransportService.process
  → Main Queue → TransportQueueRuntime.handleMain → TelemetryService / DeviceAttributeService

GET attributes
  → DeviceApiController → TransportService.process(GetAttributeRequestMsg)
  → 直查 DeviceAttributeService → HttpSessionListener → JSON 200
```

**attributes/updates（Slice 3 目标）：** 对齐 TB `subscribeToAttributes`；通知 proto 仅 `sharedUpdated` / `sharedDeleted`（`queue.proto` `AttributeUpdateNotificationMsg`）。

## Slice 1: 遥测 / 属性

- Goal: POST telemetry/attributes + GET attributes。
- Acceptance: AC-1..4
- Depends on: 003 done
- Test: `HttpDeviceTelemetryAttributeIntegrationTest`（MockMvc + asyncDispatch）
- Implementation:
  - `DeviceApiController` — 三路由
  - `TransportService.validateDeviceToken` + `DeviceAuthCallback`
  - 上行：`TransportService.process` → Main → `TransportQueueRuntime.handleMain`
  - GET attributes：直查领域 + `HttpSessionListener`
- Verification: 对应 IT
- Done: true

## Slice 2: 服务端↔设备 RPC 闭环

- Goal: 整型 requestId；504；twoway 闭环；persistent。
- Acceptance: AC-5..8
- Depends on: Slice 1
- Test: `HttpDeviceRpcIntegrationTest`（MockMvc 全链路，含 async）
- Implementation:
  - `TransportRpcService` — 管理端 `processAdminRpc`；设备面 `subscribeToRpc` / `replyToRpc`
  - `DeviceRpcController` → `TransportRpcService`（`DeferredResult`）
  - `DeviceApiController` — `GET .../rpc`、`POST .../rpc/{requestId}`
  - `TransportSessionRegistry.activeSessionId` + direct `deliver`（非持久在线 RPC 不经 Queue）
  - 持久 RPC：`DeviceRpcService.findOldestQueued` + poll 时下发
- Verification: `HttpDeviceRpcIntegrationTest` AC-5..7 绿
- Done: true

## Slice 3: 设备→云 RPC + attributes/updates

- Goal: `POST .../rpc` 等回复；`GET .../attributes/updates` 长轮询。
- Acceptance: AC-13, AC-14
- Depends on: Slice 2
- Test: 同文件或分文件 IT
- Implementation:
  - `GET .../attributes/updates` — `SubscribeToAttributeUpdatesMsg` + `HttpSyncSessionListener`
  - `POST .../rpc` — `ToServerRpcRequestMsg` → Main → `ToServerRpcHandlers` → `TransportNotifications` 回复
  - `SharedAttributeChangedEvent` + `TransportSharedAttributeListener` → shared 变更下行
  - `TransportSessionRegistry.deliverAttributeUpdate` / `deliverToServerRpcResponse`
- Verification: `HttpDeviceSlice3IntegrationTest` AC-13/14 绿
- Done: true

## Slice 4: claim / provision / OTA

- Goal: claim、provision、firmware/software。
- Acceptance: AC-9..12, AC-15
- Depends on: Slice 1
- Test: 各场景 IT
- Implementation: `TransportDeviceApiService`（SERVER claiming 属性、profile_data provision、OTA downloadRange）
- Verification: `HttpDeviceSlice4IntegrationTest` AC-9..12 绿
- Done: true

## AC → Slice

| AC | Slice |
|----|-------|
| AC-1..4 | 1 |
| AC-5..8 | 2 |
| AC-13, AC-14 | 3 |
| AC-9..12, AC-15 | 4 |

## Build log

- 2026-08-19 | Slice 1 | `DeviceApiController` + `DeviceAuthCallback` / `HttpSessionListener`；`TransportQueueRuntime.handleMain`；`HttpDeviceTelemetryAttributeIntegrationTest` AC-1..4 绿
- 2026-08-20 | Slice 2 | `TransportRpcService` + `DeviceApiController` RPC 路由；`DeviceRpcController` 接线在线投递；`HttpDeviceRpcIntegrationTest` AC-5..7 绿
- 2026-08-20 | Slice 3 | `attributes/updates` + 设备→云 `POST .../rpc`；`HttpDeviceSlice3IntegrationTest` AC-13/14 绿
- 2026-08-20 | Slice 4 | `TransportDeviceApiService` + claim/provision/OTA 路由；`HttpDeviceSlice4IntegrationTest` AC-9..12 绿
