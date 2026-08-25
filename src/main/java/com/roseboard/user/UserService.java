package com.roseboard.user;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.roseboard.common.JacksonUtils;
import com.roseboard.common.PageData;
import com.roseboard.common.security.SecurityUsers;
import com.roseboard.setting.security.DefaultSecurityUserService;
import com.roseboard.setting.security.DataScopeAuthorizer;
import com.roseboard.infrastructure.security.api.LoginResponse;
import com.roseboard.infrastructure.security.api.SecurityUser;
import com.roseboard.infrastructure.security.jwt.JwtTokenFactory;
import com.roseboard.tenant.usage.TenantUsageService;
import com.roseboard.tenant.TenantDataCleanupMapper;
import com.roseboard.user.credential.UserCredentialService;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

@Service
public class UserService {
    private final UserMapper userMapper;
    private final TenantUsageService usageService;
    private final DataScopeAuthorizer dataScopeService;
    private final DefaultSecurityUserService userDetailsService;
    private final JwtTokenFactory tokenService;
    private final UserCredentialService credentialsService;
    private final TenantDataCleanupMapper dataCleanupMapper;

    public UserService(
            UserMapper userMapper,
            TenantUsageService usageService,
            DataScopeAuthorizer dataScopeService,
            DefaultSecurityUserService userDetailsService,
            JwtTokenFactory tokenService,
            UserCredentialService credentialsService,
            TenantDataCleanupMapper dataCleanupMapper) {
        this.userMapper = userMapper;
        this.usageService = usageService;
        this.dataScopeService = dataScopeService;
        this.userDetailsService = userDetailsService;
        this.tokenService = tokenService;
        this.credentialsService = credentialsService;
        this.dataCleanupMapper = dataCleanupMapper;
    }

    public UserEntity findById(UUID userId) {
        return userMapper.selectById(userId);
    }

    public UserEntity requireById(UUID userId) {
        UserEntity user = userMapper.selectById(userId);
        if (user == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found");
        }
        return user;
    }

    public UserEntity requireScoped(UUID userId, Authentication authentication) {
        UserEntity user = requireById(userId);
        dataScopeService.requireUserScope(authentication, user);
        return user;
    }
    public LoginResponse issueUserToken(UUID userId, Authentication authentication) {
        UserEntity user = requireScoped(userId, authentication);
        SecurityUser principal = (SecurityUser) userDetailsService.loadUserByUsername(user.getEmail());
        return tokenService.login(principal);
    }

    public UserEntity save(JsonNode payload, boolean sendActivationMail, Authentication authentication) {
        UserEntity user = fromPayload(payload);
        if (user.getId() != null) {
            UserEntity existing = requireScoped(user.getId(), authentication);
            user.setTenantId(existing.getTenantId());
            user.setCustomerId(existing.getCustomerId());
            user.setCreatedTime(existing.getCreatedTime());
            user.setVersion(existing.getVersion());
            if (!dataScopeService.current(authentication).isSystemAdmin()) {
                user.setAuthority(existing.getAuthority());
            }
        } else {
            dataScopeService.requireTenant(authentication, user.getTenantId());
            if (user.getAuthority() == UserAuthority.SYS_ADMIN
                    && !dataScopeService.current(authentication).isSystemAdmin()) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                        "Only system administrators can create system administrators");
            }
        }
        SaveUserResult result = save(user);
        if (sendActivationMail && result.activateToken() != null && result.user().getEmail() != null) {
            credentialsService.sendActivationEmail(result.user(), result.activateToken());
        }
        return result.user();
    }

    public void delete(UUID userId, Authentication authentication) {
        requireScoped(userId, authentication);
        deleteById(userId);
    }

    public PageData<UserEntity> findUsers(Authentication authentication,
                                           long pageSize, long page, String textSearch) {
        SecurityUsers scope = dataScopeService.current(authentication);
        if (scope.getCustomerId() != null) {
            return findCustomerUsers(scope.getCustomerId(), pageSize, page, textSearch);
        }
        if (scope.getTenantId() != null) {
            return findTenantUsers(scope.getTenantId(), pageSize, page, textSearch);
        }
        return findPage(pageSize, page, textSearch);
    }

    public PageData<UserEmailInfo> findUserInfos(Authentication authentication,
                                                   long pageSize, long page, String textSearch) {
        SecurityUsers scope = dataScopeService.current(authentication);
        return findUserInfos(scope.getTenantId(), scope.getCustomerId(), pageSize, page, textSearch);
    }
    public List<UserEmailInfo> findUserInfoList(Authentication authentication) {
        SecurityUsers scope = dataScopeService.current(authentication);
        return userMapper.selectUserInfos(scope.getTenantId(), scope.getCustomerId(), null).stream()
                .map(user -> new UserEmailInfo(user.getId(), user.getEmail(), user.getFirstName(), user.getLastName()))
                .toList();
    }

    public PageData<UserEntity> findTenantUsers(UUID tenantId, long pageSize, long page,
                                                String textSearch, Authentication authentication) {
        dataScopeService.requireTenant(authentication, tenantId);
        return findTenantUsers(tenantId, pageSize, page, textSearch);
    }

    public PageData<UserEntity> findCustomerUsers(UUID customerId, long pageSize, long page,
                                                  String textSearch, Authentication authentication) {
        dataScopeService.requireCustomer(authentication, customerId);
        return findCustomerUsers(customerId, pageSize, page, textSearch);
    }

    public UserEntity fromPayload(JsonNode payload) {
        if (!(payload instanceof ObjectNode object)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "User payload must be an object");
        }
        return JacksonUtils.objectMapper().treeToValue(object, UserEntity.class);
    }

    public List<UserEntity> findByIds(Collection<UUID> userIds) {
        return userMapper.selectBatchIds(userIds);
    }

    public UserEntity findByEmail(String email) {
        return email == null ? null : userMapper.findByEmail(email);
    }
    public PageData<UserEntity> findPage(long pageSize, long page, String textSearch) {
        return toPageData(userMapper.selectPage(pageSize, page, textSearch), pageSize, page);
    }

    public PageData<UserEntity> findTenantUsers(
            UUID tenantId, long pageSize, long page, String textSearch) {
        return toPageData(userMapper.selectPageByTenant(tenantId, pageSize, page, textSearch), pageSize, page);
    }

    public PageData<UserEntity> findCustomerUsers(
            UUID customerId, long pageSize, long page, String textSearch) {
        return toPageData(userMapper.selectPageByCustomer(customerId, pageSize, page, textSearch), pageSize, page);
    }

    public PageData<UserEmailInfo> findUserInfos(
            UUID tenantId, UUID customerId, long pageSize, long page, String textSearch) {
        Page<UserEntity> result = userMapper.selectPageUserInfos(
                tenantId, customerId, pageSize, page, textSearch);
        var records = result.getRecords().stream()
                .map(user -> new UserEmailInfo(user.getId(), user.getEmail(), user.getFirstName(), user.getLastName()))
                .toList();
        return new PageData<>(records, pageSize, page, result.getTotal());
    }

    private PageData<UserEntity> toPageData(Page<UserEntity> result, long pageSize, long page) {
        return new PageData<>(result.getRecords(), pageSize, page, result.getTotal());
    }

    @Transactional(rollbackFor = Exception.class)
    public SaveUserResult save(UserEntity user) {
        long now = System.currentTimeMillis();
        if (user.getId() != null) {
            UserEntity existing = requireById(user.getId());
            user.setTenantId(existing.getTenantId());
            user.setCustomerId(existing.getCustomerId());
            user.setCreatedTime(existing.getCreatedTime());
            user.setVersion(existing.getVersion());
            userMapper.updateById(user);
            return new SaveUserResult(userMapper.selectById(user.getId()), null);
        }
        if (user.getTenantId() != null) {
            usageService.requireEntityQuota(user.getTenantId(), TenantUsageService.MAX_USERS);
        }
        user.setId(UUID.randomUUID());
        user.setCreatedTime(now);
        user.setVersion(1L);
        userMapper.insert(user);

        return new SaveUserResult(user, credentialsService.createForUser(user.getId(), now));
    }

    @Transactional(rollbackFor = Exception.class)
    public UserEntity createOAuth2User(UserEntity user, boolean activate) {
        SaveUserResult result = save(user);
        if (activate) {
            credentialsService.activate(result.user().getId());
        }
        return result.user();
    }

    @Transactional(rollbackFor = Exception.class)
    public void deleteById(UUID userId) {
        dataCleanupMapper.deleteNotificationsByUser(userId);
        dataCleanupMapper.deleteApiKeysByUser(userId);
        dataCleanupMapper.deleteSettingsByUser(userId);
        dataCleanupMapper.deleteCredentialsByUser(userId);
        userMapper.deleteById(userId);
    }

    public record SaveUserResult(UserEntity user, String activateToken) { }

}
