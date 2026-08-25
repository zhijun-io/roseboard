package com.roseboard.device.rpc;

import com.roseboard.common.JacksonUtils;
import com.roseboard.device.DeviceEntity;
import com.roseboard.device.DeviceMapper;
import com.roseboard.device.DeviceService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.roseboard.common.PageData;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.Optional;
import java.util.UUID;

@Service
public class DeviceRpcService {
    private final DeviceRpcMapper rpcMapper;
    private final DeviceMapper deviceMapper;
    private final DeviceService deviceService;

    public DeviceRpcService(DeviceRpcMapper rpcMapper, DeviceMapper deviceMapper,
                            DeviceService deviceService) {
        this.rpcMapper = rpcMapper;
        this.deviceMapper = deviceMapper;
        this.deviceService = deviceService;
    }

    @Transactional(rollbackFor = Exception.class)
    public DeviceRpcEntity create(UUID tenantId, UUID deviceId, JsonNode request, long expirationTime,
                                  JsonNode additionalInfo) {
        requireDeviceInTenant(tenantId, deviceId);
        if (request == null || request.isNull()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "RPC request is required");
        }
        if (!request.hasNonNull("method")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "method is required");
        }
        DeviceRpcEntity entity = new DeviceRpcEntity();
        entity.setId(UUID.randomUUID());
        entity.setCreatedTime(System.currentTimeMillis());
        entity.setTenantId(tenantId);
        entity.setDeviceId(deviceId);
        entity.setExpirationTime(expirationTime);
        entity.setRequest(request);
        entity.setStatus(RpcStatus.QUEUED.name());
        entity.setAdditionalInfo(additionalInfo);
        rpcMapper.insert(entity);
        return rpcMapper.selectById(entity.getId());
    }

    public DeviceRpcEntity findById(UUID rpcId) {
        DeviceRpcEntity entity = rpcMapper.selectById(rpcId);
        if (entity == null || RpcStatus.DELETED.name().equals(entity.getStatus())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "RPC not found");
        }
        return entity;
    }

    public DeviceRpcEntity requireScoped(UUID rpcId, Authentication authentication) {
        DeviceRpcEntity rpc = findById(rpcId);
        deviceService.requireScoped(rpc.getDeviceId(), authentication);
        return rpc;
    }

    public DeviceRpcEntity findById(UUID tenantId, UUID rpcId) {
        DeviceRpcEntity entity = findById(rpcId);
        if (!tenantId.equals(entity.getTenantId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "RPC not found");
        }
        return entity;
    }

    public Optional<DeviceRpcEntity> findOldestQueued(UUID tenantId, UUID deviceId) {
        requireDeviceInTenant(tenantId, deviceId);
        LambdaQueryWrapper<DeviceRpcEntity> q = new LambdaQueryWrapper<DeviceRpcEntity>()
                .eq(DeviceRpcEntity::getTenantId, tenantId)
                .eq(DeviceRpcEntity::getDeviceId, deviceId)
                .eq(DeviceRpcEntity::getStatus, RpcStatus.QUEUED.name())
                .orderByAsc(DeviceRpcEntity::getCreatedTime)
                .last("LIMIT 1");
        return Optional.ofNullable(rpcMapper.selectOne(q));
    }

    @Transactional(rollbackFor = Exception.class)
    public DeviceRpcEntity claimQueued(UUID tenantId, UUID rpcId, int transportRequestId) {
        return markSent(tenantId, rpcId, transportRequestId);
    }

    @Transactional(rollbackFor = Exception.class)
    public Optional<DeviceRpcEntity> claimOldestQueued(UUID tenantId, UUID deviceId, int transportRequestId) {
        return findOldestQueued(tenantId, deviceId)
                .map(rpc -> claimQueued(tenantId, rpc.getId(), transportRequestId));
    }

    @Transactional(rollbackFor = Exception.class)
    public void completeSentReply(UUID tenantId, UUID deviceId, int transportRequestId, JsonNode response) {
        findSentByTransportRequestId(tenantId, deviceId, transportRequestId)
                .ifPresent(rpc -> updateStatus(tenantId, rpc.getId(), RpcStatus.SUCCESSFUL, response, null));
    }

    private DeviceRpcEntity markSent(UUID tenantId, UUID rpcId, int transportRequestId) {
        DeviceRpcEntity entity = findById(tenantId, rpcId);
        if (!RpcStatus.QUEUED.name().equals(entity.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "RPC is not queued");
        }
        ObjectNode additionalInfo = JacksonUtils.asObject(entity.getAdditionalInfo());
        additionalInfo.put("transportRequestId", transportRequestId);
        return updateStatus(tenantId, rpcId, RpcStatus.SENT, null, additionalInfo);
    }

    private Optional<DeviceRpcEntity> findSentByTransportRequestId(UUID tenantId, UUID deviceId,
                                                                     int transportRequestId) {
        requireDeviceInTenant(tenantId, deviceId);
        ObjectNode probe = JacksonUtils.newObjectNode();
        probe.put("transportRequestId", transportRequestId);
        LambdaQueryWrapper<DeviceRpcEntity> q = new LambdaQueryWrapper<DeviceRpcEntity>()
                .eq(DeviceRpcEntity::getTenantId, tenantId)
                .eq(DeviceRpcEntity::getDeviceId, deviceId)
                .eq(DeviceRpcEntity::getStatus, RpcStatus.SENT.name())
                .apply("additional_info @> {0}::jsonb", probe.toString())
                .last("LIMIT 1");
        return Optional.ofNullable(rpcMapper.selectOne(q));
    }

    public PageData<DeviceRpcEntity> findPageByDevice(UUID tenantId, UUID deviceId, RpcStatus status,
                                                     long pageSize, long page) {
        requireDeviceInTenant(tenantId, deviceId);
        if (status == RpcStatus.DELETED) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "RpcStatus: DELETED");
        }
        LambdaQueryWrapper<DeviceRpcEntity> q = new LambdaQueryWrapper<DeviceRpcEntity>()
                .eq(DeviceRpcEntity::getTenantId, tenantId)
                .eq(DeviceRpcEntity::getDeviceId, deviceId)
                .ne(DeviceRpcEntity::getStatus, RpcStatus.DELETED.name())
                .eq(status != null, DeviceRpcEntity::getStatus, status == null ? null : status.name())
                .orderByDesc(DeviceRpcEntity::getCreatedTime);
        Page<DeviceRpcEntity> result = rpcMapper.selectPage(new Page<>(page + 1L, pageSize), q);
        return new PageData<>(result.getRecords(), pageSize, page, result.getTotal());
    }

    @Transactional(rollbackFor = Exception.class)
    public DeviceRpcEntity updateStatus(UUID tenantId, UUID rpcId, RpcStatus status, JsonNode response,
                                        JsonNode additionalInfo) {
        DeviceRpcEntity entity = findById(tenantId, rpcId);
        if (status == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "RPC status is required");
        }
        entity.setStatus(status.name());
        if (response != null) {
            entity.setResponse(response);
        }
        if (additionalInfo != null) {
            entity.setAdditionalInfo(additionalInfo);
        }
        rpcMapper.updateById(entity);
        return rpcMapper.selectById(rpcId);
    }

    @Transactional(rollbackFor = Exception.class)
    public void delete(UUID tenantId, UUID rpcId) {
        DeviceRpcEntity entity = findById(tenantId, rpcId);
        entity.setStatus(RpcStatus.DELETED.name());
        rpcMapper.updateById(entity);
    }

    private void requireDeviceInTenant(UUID tenantId, UUID deviceId) {
        DeviceEntity device = deviceMapper.selectById(deviceId);
        if (device == null || !tenantId.equals(device.getTenantId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Device not found");
        }
    }
}
