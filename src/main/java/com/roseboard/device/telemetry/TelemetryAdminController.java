package com.roseboard.device.telemetry;

import com.roseboard.audit.AuditActions;
import com.roseboard.infrastructure.audit.Audited;
import com.roseboard.infrastructure.audit.event.EntityType;
import com.roseboard.device.DeviceEntity;
import com.roseboard.device.DeviceService;
import com.roseboard.infrastructure.security.api.RequirePermission;
import com.roseboard.infrastructure.security.api.Operation;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Admin telemetry HTTP for DEVICE entities (TB TelemetryController DEVICE subset).
 * Not the device-facing {@code /api/http} surface.
 */
@RestController
@RequestMapping("/api/plugins/telemetry")
public class TelemetryAdminController {

    private final DeviceService deviceService;
    private final TelemetryService telemetryService;

    public TelemetryAdminController(DeviceService deviceService, TelemetryService telemetryService) {
        this.deviceService = deviceService;
        this.telemetryService = telemetryService;
    }

    /**
     * 查询设备属性键列表。
     */
/**
 * 设备遥测管理接口：查询、写入和删除设备时序数据。
 */
/**
 * 查询设备属性键列表。
 */

    @GetMapping("/DEVICE/{deviceId}/keys/timeseries")
    @RequirePermission(resource = EntityType.DEVICE, operation = Operation.READ)
    public List<String> keys(@PathVariable UUID deviceId, Authentication authentication) {
        DeviceEntity device = deviceService.requireScoped(deviceId, authentication);
        return telemetryService.keys(device.getTenantId(), deviceId);
    }

    /**
     * 查询设备最新或历史遥测。
     */
    @GetMapping(value = "/DEVICE/{deviceId}/values/timeseries")
    @RequirePermission(resource = EntityType.DEVICE, operation = Operation.READ)
    public Map<String, List<Map<String, Object>>> latestOrHistory(
            @PathVariable UUID deviceId,
            @RequestParam(required = false) String keys,
            @RequestParam(required = false) Long startTs,
            @RequestParam(required = false) Long endTs,
            @RequestParam(defaultValue = "100") int limit,
            @RequestParam(defaultValue = "DESC") String orderBy,
            Authentication authentication) {
        DeviceEntity device = deviceService.requireScoped(deviceId, authentication);
        if (startTs != null && endTs != null) {
            return history(device, deviceId, keys, startTs, endTs, limit, orderBy);
        }
        return latest(device, deviceId, keys);
    }

    /**
     * 查询设备历史遥测。
     */
    @GetMapping("/DEVICE/{deviceId}/values/timeseries/history")
    @RequirePermission(resource = EntityType.DEVICE, operation = Operation.READ)
    public Map<String, List<Map<String, Object>>> historyExplicit(
            @PathVariable UUID deviceId,
            @RequestParam(required = false) String keys,
            @RequestParam long startTs,
            @RequestParam long endTs,
            @RequestParam(defaultValue = "100") int limit,
            @RequestParam(defaultValue = "DESC") String orderBy,
            Authentication authentication) {
        DeviceEntity device = deviceService.requireScoped(deviceId, authentication);
        return history(device, deviceId, keys, startTs, endTs, limit, orderBy);
    }

    /**
     * 创建或更新资源。
     */
    @PostMapping("/DEVICE/{deviceId}/timeseries/{scope}")
    @ResponseStatus(HttpStatus.OK)
    @RequirePermission(resource = EntityType.DEVICE, operation = Operation.WRITE)
    @Audited(action = AuditActions.DEVICE_TELEMETRY_UPDATED, entityType = EntityType.DEVICE,
            entityId = "#deviceId")
    public void save(@PathVariable UUID deviceId,
                     @PathVariable String scope,
                     @RequestBody JsonNode body,
                     Authentication authentication) {
        DeviceEntity device = deviceService.requireScoped(deviceId, authentication);
        List<TelemetryWrite> writes = parseWrites(body);
        telemetryService.saveBatch(device.getTenantId(), deviceId, writes);
    }

    /**
     * 删除资源。
     */
    @DeleteMapping("/DEVICE/{deviceId}/timeseries/delete")
    @ResponseStatus(HttpStatus.OK)
    @RequirePermission(resource = EntityType.DEVICE, operation = Operation.WRITE)
    @Audited(action = AuditActions.DEVICE_TELEMETRY_DELETED, entityType = EntityType.DEVICE,
            entityId = "#deviceId")
    public Map<String, Integer> delete(@PathVariable UUID deviceId,
                                       @RequestParam String keys,
                                       @RequestParam(defaultValue = "false") boolean deleteAllDataForKeys,
                                       @RequestParam(required = false) Long startTs,
                                       @RequestParam(required = false) Long endTs,
                                       @RequestParam(defaultValue = "true") boolean rewriteLatestIfDeleted,
                                       Authentication authentication) {
        DeviceEntity device = deviceService.requireScoped(deviceId, authentication);
        List<String> keyList = csv(keys);
        if (keyList.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Telemetry keys are required");
        }
        long from;
        long to;
        if (deleteAllDataForKeys) {
            from = 0L;
            to = Long.MAX_VALUE;
        } else {
            if (startTs == null || endTs == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "startTs and endTs are required when deleteAllDataForKeys is false");
            }
            from = startTs;
            to = endTs;
        }
        Map<String, Integer> deleted = new LinkedHashMap<>();
        for (String key : keyList) {
            deleted.put(key, telemetryService.remove(device.getTenantId(), deviceId, key,
                    from, to, rewriteLatestIfDeleted));
        }
        return deleted;
    }

    private Map<String, List<Map<String, Object>>> latest(DeviceEntity device, UUID deviceId, String keys) {
        List<TelemetryLatest> points;
        if (!StringUtils.hasText(keys)) {
            points = telemetryService.latestAll(device.getTenantId(), deviceId);
        } else {
            points = telemetryService.latest(device.getTenantId(), deviceId, csv(keys));
        }
        Map<String, List<Map<String, Object>>> result = new LinkedHashMap<>();
        for (TelemetryLatest point : points) {
            if (point == null) {
                continue;
            }
            result.put(point.key(), List.of(tsValue(point.timestampMs(), point.value())));
        }
        return result;
    }

    private Map<String, List<Map<String, Object>>> history(DeviceEntity device, UUID deviceId, String keys,
                                                           long startTs, long endTs, int limit, String orderBy) {
        List<String> keyList = csv(keys);
        if (keyList.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Telemetry keys are required");
        }
        TelemetryOrder order;
        try {
            order = TelemetryOrder.valueOf(orderBy.trim().toUpperCase());
        } catch (RuntimeException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid orderBy");
        }
        List<TelemetryPoint> points = telemetryService.history(device.getTenantId(), deviceId,
                keyList, startTs, endTs, limit, order);
        Map<String, List<Map<String, Object>>> result = new LinkedHashMap<>();
        for (TelemetryPoint point : points) {
            result.computeIfAbsent(point.key(), ignored -> new ArrayList<>())
                    .add(tsValue(point.timestampMs(), point.value()));
        }
        return result;
    }

    private static List<TelemetryWrite> parseWrites(JsonNode body) {
        return TelemetryPayloadParser.parseWrites(body);
    }

    private static Map<String, Object> tsValue(long ts, Object value) {
        Map<String, Object> point = new LinkedHashMap<>(2);
        point.put("ts", ts);
        point.put("value", value);
        return point;
    }

    private static List<String> csv(String value) {
        return StringUtils.hasText(value)
                ? List.of(StringUtils.tokenizeToStringArray(value, ","))
                : List.of();
    }
}
