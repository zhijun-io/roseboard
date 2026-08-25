package com.roseboard.infrastructure.security.authorization;

import com.roseboard.infrastructure.security.SecurityUserResolver;
import com.roseboard.setting.security.DataScopeAuthorizer;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.roseboard.common.security.SecurityUsers;
import com.roseboard.customer.CustomerMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.Authentication;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DataScopeAuthorizerTest {

    @Mock
    CustomerMapper customerMapper;

    @Mock
    SecurityUserResolver securityUserResolver;

    @Mock
    Authentication authentication;

    @Test
    void rejectsNullCustomerIdBeforeCheckingCustomerMapper() {
        DataScopeAuthorizer service = new DataScopeAuthorizer(customerMapper, securityUserResolver);

        assertFalse(service.hasCustomerAccess(authentication, null));

        verifyNoInteractions(customerMapper);
    }

    @Test
    void rejectsNullTenantIdEvenForAnonymousPrincipal() {
        DataScopeAuthorizer service = serviceWith(SecurityUsers.anonymous());

        assertThrows(ResponseStatusException.class,
                () -> service.requireTenant(authentication, null));

        verifyNoInteractions(customerMapper);
    }

    @Test
    void checksTenantOwnershipWithoutLoadingCustomerEntity() {
        UUID customerId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();
        when(customerMapper.selectCount(any(Wrapper.class))).thenReturn(1L);
        DataScopeAuthorizer service = serviceWith(SecurityUsers.of(null, tenantId, null));

        assertTrue(service.hasCustomerAccess(authentication, customerId));

        verify(customerMapper).selectCount(any(Wrapper.class));
    }

    private DataScopeAuthorizer serviceWith(SecurityUsers identity) {
        when(securityUserResolver.resolve(authentication)).thenReturn(identity);
        return new DataScopeAuthorizer(customerMapper, securityUserResolver);
    }
}
