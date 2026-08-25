package com.roseboard.device.rpc;

import com.roseboard.audit.AuditActions;
import com.roseboard.infrastructure.audit.Audited;
import com.roseboard.infrastructure.audit.event.AuditEvent;
import com.roseboard.infrastructure.audit.event.AuditTarget;
import com.roseboard.infrastructure.audit.event.EntityType;
import com.roseboard.device.DeviceEntity;
import com.roseboard.device.DeviceService;
import com.roseboard.common.PageData;
import com.roseboard.common.security.SecurityUsers;
import com.roseboard.setting.security.DataScopeAuthorizer;
import com.roseboard.infrastructure.security.api.RequirePermission;
import com.roseboard.infrastructure.security.api.Operation;
import com.roseboard.infrastructure.transport.TransportRpcService;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.context.request.async.DeferredResult;
import tools.jackson.databind.JsonNode;

import java.util.UUID;

/**
 * HTTP surface aligned with ThingsBoard {@code RpcV2Controller} ({@code /api/rpc}).
 */
@RestController
@RequestMapping("/api/rpc")
public class DeviceRpcController {

    private final DeviceRpcService rpcService;
    private final DeviceService deviceService;
    private final DataScopeAuthorizer dataScopeService;
    private final TransportRpcService transportRpcService;
    private final ApplicationEventPublisher eventPublisher;

    public DeviceRpcController(DeviceRpcService rpcService, DeviceService deviceService,
                               DataScopeAuthorizer dataScopeService,
                               TransportRpcService transportRpcService,
                               ApplicationEventPublisher eventPublisher) {
        this.rpcService = rpcService;
        this.deviceService = deviceService;
        this.dataScopeService = dataScopeService;
        this.transportRpcService = transportRpcService;
        this.eventPublisher = eventPublisher;
    }

    /**
     * 向设备发送单向 RPC。
     */
    @PostMapping("/oneway/{deviceId}")
    @RequirePermission(resource = EntityType.DEVICE, operation = Operation.WRITE)
    public DeferredResult<ResponseEntity> oneWay(@PathVariable UUID deviceId, @RequestBody JsonNode body,
                                                    Authentication authentication) {
        return handleRpcRequest(deviceId, body, authentication, true);
    }

    /**
     * 向设备发送双向 RPC。
     */
    @PostMapping("/twoway/{deviceId}")
    @RequirePermission(resource = EntityType.DEVICE, operation = Operation.WRITE)
    public DeferredResult<ResponseEntity> twoWay(@PathVariable UUID deviceId, @RequestBody JsonNode body,
                                                   Authentication authentication) {
        return handleRpcRequest(deviceId, body, authentication, false);
    }

    /**
     * 查询持久化 RPC。
     */
    @GetMapping("/persistent/{rpcId}")
    @RequirePermission(resource = EntityType.DEVICE, operation = Operation.READ)
    public DeviceRpcEntity getPersistedRpc(@PathVariable UUID rpcId, Authentication authentication) {
        return rpcService.requireScoped(rpcId, authentication);
    }

    /**
     * 按设备分页查询持久化 RPC。
     */
    @GetMapping("/persistent/device/{deviceId}")
    @RequirePermission(resource = EntityType.DEVICE, operation = Operation.READ)
    public PageData<DeviceRpcEntity> getPersistedRpcByDevice(
            @PathVariable UUID deviceId,
            @RequestParam long pageSize,
            @RequestParam long page,
            @RequestParam(required = false) RpcStatus rpcStatus,
            Authentication authentication) {
        DeviceEntity device = deviceService.requireScoped(deviceId, authentication);
        return rpcService.findPageByDevice(device.getTenantId(), deviceId, rpcStatus, pageSize, page);
    }

    /**
     * 删除持久化 RPC。
     */
    @DeleteMapping("/persistent/{rpcId}")
    @RequirePermission(resource = EntityType.DEVICE, operation = Operation.DELETE)
    @Audited(action = AuditActions.DEVICE_RPC_DELETED, entityType = EntityType.DEVICE, entityId = "#rpcId")
    public void deleteRpc(@PathVariable UUID rpcId, Authentication authentication) {
        DeviceRpcEntity existing = rpcService.requireScoped(rpcId, authentication);
        SecurityUsers scope = dataScopeService.current(authentication);
        if (!scope.isSystemAdmin() && scope.getCustomerId() != null) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Tenant admin required");
        }
        rpcService.delete(existing.getTenantId(), rpcId);
    }

    private DeferredResult<ResponseEntity> handleRpcRequest(UUID deviceId, JsonNode body,
                                                                Authentication authentication, boolean oneWay) {
        DeviceEntity device = deviceService.requireScoped(deviceId, authentication);
        SecurityUsers principal = dataScopeService.current(authentication);
        DeferredResult<ResponseEntity> result = new DeferredResult<>();
        attachRpcAudit(result, device, principal, body, oneWay);
        transportRpcService.processAdminRpc(device.getTenantId(), deviceId, body, oneWay, result);
        return result;
    }

    private void attachRpcAudit(DeferredResult<ResponseEntity> result, DeviceEntity device,
                                SecurityUsers principal, JsonNode body, boolean oneWay) {
        AuditTarget target = AuditTarget.device(device.getId(), device.getTenantId());
        String detail = rpcDetail(body, oneWay);
        result.onCompletion(() -> publishRpcAudit(result, principal, target, detail));
    }

    private void publishRpcAudit(DeferredResult<ResponseEntity> result, SecurityUsers principal,
                                 AuditTarget target, String detail) {
        ResponseEntity<?> response = (ResponseEntity<?>) result.getResult();
        if (response != null && response.getStatusCode().is2xxSuccessful()) {
            eventPublisher.publishEvent(AuditEvent.success(
                    AuditActions.DEVICE_RPC_SENT, principal, target, detail, null));
            return;
        }
        String failureMessage = response == null
                ? "RPC request timed out or was interrupted"
                : "HTTP " + response.getStatusCode().value();
        eventPublisher.publishEvent(AuditEvent.failure(
                AuditActions.DEVICE_RPC_SENT, principal, target, detail, failureMessage, null));
    }

    private static String rpcDetail(JsonNode body, boolean oneWay) {
        if (body == null || !body.has("method")) {
            return "{\"oneWay\":" + oneWay + "}";
        }
        return "{\"oneWay\":" + oneWay + ",\"method\":\"" + body.get("method").asText() + "\"}";
    }
}
