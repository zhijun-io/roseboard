package com.roseboard.setting.security;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.roseboard.customer.CustomerEntity;
import com.roseboard.customer.CustomerMapper;
import com.roseboard.common.security.SecurityUsers;
import com.roseboard.user.UserEntity;
import com.roseboard.infrastructure.security.SecurityUserResolver;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.Objects;
import java.util.UUID;

@Service("dataScopeAuthorizer")
public class DataScopeAuthorizer {
    private final CustomerMapper customerMapper;
    private final SecurityUserResolver securityUserResolver;

    public DataScopeAuthorizer(CustomerMapper customerMapper, SecurityUserResolver securityUserResolver) {
        this.customerMapper = customerMapper;
        this.securityUserResolver = securityUserResolver;
    }

    public SecurityUsers current(Authentication authentication) {
        return securityUserResolver.resolve(authentication);
    }

    public void requireTenant(Authentication authentication, UUID tenantId) {
        SecurityUsers scope = current(authentication);
        if (tenantId == null || (!scope.isSystemAdmin()
                && !Objects.equals(tenantId, scope.getTenantId()))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Tenant scope denied");
        }
    }

    public UUID requireTenantId(Authentication authentication) {
        UUID tenantId = current(authentication).getTenantId();
        if (tenantId == null) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Tenant scope required");
        }
        return tenantId;
    }

    public UUID requireUserId(Authentication authentication) {
        UUID userId = current(authentication).getUserId();
        if (userId == null) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "User scope required");
        }
        return userId;
    }

    public void requireUserScope(Authentication authentication, UserEntity user) {
        if (user.getCustomerId() != null) {
            requireCustomer(authentication, user.getCustomerId());
        } else {
            requireTenant(authentication, user.getTenantId());
        }
    }

    public void requireCurrentUserOrAdmin(Authentication authentication, UUID userId) {
        if (userId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "userId is required");
        }
        SecurityUsers scope = current(authentication);
        if (scope.isSystemAdmin() || Objects.equals(userId, scope.getUserId())) {
            return;
        }
        throw new ResponseStatusException(HttpStatus.FORBIDDEN, "User scope denied");
    }

    public void requireCustomer(Authentication authentication, UUID customerId) {
        if (!hasCustomerAccess(authentication, customerId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Customer scope denied");
        }
    }

    public boolean hasCustomerAccess(Authentication authentication, UUID customerId) {
        if (customerId == null) {
            return false;
        }
        SecurityUsers scope = current(authentication);
        if (scope.isSystemAdmin() || Objects.equals(customerId, scope.getCustomerId())) {
            return true;
        }
        if (scope.getTenantId() != null) {
            return customerMapper.selectCount(new LambdaQueryWrapper<CustomerEntity>()
                    .eq(CustomerEntity::getId, customerId)
                    .eq(CustomerEntity::getTenantId, scope.getTenantId())) > 0;
        }
        return false;
    }

    public boolean isCurrentUser(Authentication authentication, UUID userId) {
        return userId != null && userId.equals(current(authentication).getUserId());
    }

    public void requireSystemAdmin(Authentication authentication) {
        if (!current(authentication).isSystemAdmin()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "System administrator scope required");
        }
    }

}
