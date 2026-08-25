package com.roseboard.apikey;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.roseboard.common.PageData;
import com.roseboard.setting.security.DataScopeAuthorizer;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.UUID;

@Service
public class ApiKeyService {
    private static final SecureRandom RANDOM = new SecureRandom();

    private final ApiKeyMapper mapper;
    private final DataScopeAuthorizer dataScopeService;

    public ApiKeyService(ApiKeyMapper mapper, DataScopeAuthorizer dataScopeService) {
        this.mapper = mapper;
        this.dataScopeService = dataScopeService;
    }

    public PageData<ApiKeyEntity> page(UUID userId, long pageSize, long page, Authentication authentication) {
        dataScopeService.requireCurrentUserOrAdmin(authentication, userId);
        Page<ApiKeyEntity> result = mapper.selectPage(new Page<>(page + 1L, pageSize),
                new LambdaQueryWrapper<ApiKeyEntity>().eq(ApiKeyEntity::getUserId, userId)
                        .orderByDesc(ApiKeyEntity::getCreatedTime));
        return new PageData<>(result.getRecords(), pageSize, page, result.getTotal());
    }

    @Transactional(rollbackFor = Exception.class)
    public ApiKeyEntity create(UUID userId, ApiKeyRequest request, Authentication authentication) {
        dataScopeService.requireCurrentUserOrAdmin(authentication, userId);
        String value = randomValue();
        ApiKeyEntity key = new ApiKeyEntity();
        key.setId(UUID.randomUUID());
        key.setCreatedTime(System.currentTimeMillis());
        key.setUserId(userId);
        key.setKeyHash(hash(value));
        key.setEnabled(request.getEnabled() == null || request.getEnabled());
        key.setValue(value);
        key.setDescription(request.getDescription());
        mapper.insert(key);
        return key;
    }

    @Transactional(rollbackFor = Exception.class)
    public void delete(UUID id, Authentication authentication) {
        ApiKeyEntity key = requireById(id);
        dataScopeService.requireCurrentUserOrAdmin(authentication, key.getUserId());
        mapper.deleteById(id);
    }

    @Transactional(rollbackFor = Exception.class)
    public ApiKeyEntity update(UUID id, ApiKeyUpdateRequest request, Authentication authentication) {
        ApiKeyEntity key = requireById(id);
        dataScopeService.requireCurrentUserOrAdmin(authentication, key.getUserId());
        if (request.enabled() != null) {
            key.setEnabled(request.enabled());
        }
        if (request.description() != null) {
            key.setDescription(request.description());
        }
        mapper.updateById(key);
        return key;
    }

    public ApiKeyEntity requireById(UUID id) {
        ApiKeyEntity key = mapper.selectById(id);
        if (key == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "API key not found");
        }
        return key;
    }

    private static String randomValue() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    private static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to hash API key", exception);
        }
    }
}
