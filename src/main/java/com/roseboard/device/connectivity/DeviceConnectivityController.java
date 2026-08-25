package com.roseboard.device.connectivity;

import com.roseboard.infrastructure.audit.event.EntityType;
import com.roseboard.device.DeviceService;
import com.roseboard.infrastructure.security.api.RequirePermission;
import com.roseboard.infrastructure.security.api.Operation;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.RequestMethod;
import jakarta.servlet.http.HttpServletRequest;
import tools.jackson.databind.JsonNode;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * 设备连接状态接口：查询设备连接状态和连接命令。
 */
@RestController
@RequestMapping("/api/devices")
public class DeviceConnectivityController {
    private final DeviceService deviceService;
    private final DeviceConnectivityStateService stateService;
    private final DeviceConnectivityCommandService commandService;

    public DeviceConnectivityController(DeviceService deviceService,
                                        DeviceConnectivityStateService stateService,
                                        DeviceConnectivityCommandService commandService) {
        this.deviceService = deviceService;
        this.stateService = stateService;
        this.commandService = commandService;
    }

    /**
     * 查询单个资源。
     */
    @GetMapping("/{deviceId}/connectivity")
    @RequirePermission(resource = EntityType.DEVICE, operation = Operation.READ)
    public DeviceConnectivityState get(@PathVariable UUID deviceId,
                                       Authentication authentication) {
        var device = deviceService.requireScoped(deviceId, authentication);
        return stateService.find(device.getTenantId(), deviceId);
    }

    /**
     * 处理 /{deviceId}/connectivity/commands 对应的接口请求。
     */
    @GetMapping("/{deviceId}/connectivity/commands")
    @RequirePermission(resource = EntityType.DEVICE, operation = Operation.READ)
    public JsonNode getPublishCommands(@PathVariable UUID deviceId,
                                       Authentication authentication,
                                       HttpServletRequest request) {
        var device = deviceService.requireScoped(deviceId, authentication);
        return commandService.find(baseUrl(request), device);
    }

    private static String baseUrl(HttpServletRequest request) {
        String scheme = request.getHeader("X-Forwarded-Proto");
        if (scheme == null || scheme.isBlank()) {
            scheme = request.getScheme();
        }
        String host = request.getHeader("X-Forwarded-Host");
        if (host == null || host.isBlank()) {
            host = request.getServerName();
            int port = request.getServerPort();
            if (port > 0 && !("http".equalsIgnoreCase(scheme) && port == 80)
                    && !("https".equalsIgnoreCase(scheme) && port == 443)) {
                host += ":" + port;
            }
        }
        return scheme + "://" + host;
    }
}
