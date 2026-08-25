package com.roseboard.customer;

import com.roseboard.audit.AuditActions;
import com.roseboard.common.PageData;
import com.roseboard.common.security.SecurityUsers;
import com.roseboard.infrastructure.audit.Audited;
import com.roseboard.infrastructure.audit.event.EntityType;
import com.roseboard.setting.security.DataScopeAuthorizer;
import com.roseboard.infrastructure.security.api.RequirePermission;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

import com.roseboard.infrastructure.security.api.Operation;

/**
 * 客户资源接口：提供客户查询、创建和删除，并执行数据范围校验。
 */
@RestController
@RequestMapping("/api/customers")
public class CustomerController {

    private final CustomerService customerService;
    private final DataScopeAuthorizer dataScopeService;

    public CustomerController(CustomerService customerService, DataScopeAuthorizer dataScopeService) {
        this.customerService = customerService;
        this.dataScopeService = dataScopeService;
    }

    /**
     * 按客户 ID 查询客户。
     */
    @GetMapping("/{customerId}")
    @RequirePermission(resource = EntityType.CUSTOMER, operation = Operation.READ)
    public CustomerEntity getCustomerById(@PathVariable UUID customerId, Authentication authentication) {
        dataScopeService.requireCustomer(authentication, customerId);
        return customerService.requireById(customerId);
    }

    /**
     * 处理 customerIds 对应的接口请求。
     */
    @GetMapping(params = "customerIds")
    @RequirePermission(resource = EntityType.CUSTOMER, operation = Operation.READ)
    public List<CustomerEntity> getCustomersByIds(@RequestParam UUID[] customerIds,
                                                  Authentication authentication) {
        dataScopeService.requireSystemAdmin(authentication);
        return customerService.findByIds(List.of(customerIds));
    }

    /**
     * 创建或更新客户。
     */
    @PostMapping
    @RequirePermission(resource = EntityType.CUSTOMER, operation = Operation.WRITE)
    @Audited(action = "#customer.id != null ? T(com.roseboard.audit.AuditActions).CUSTOMER_UPDATED : T(com.roseboard.audit.AuditActions).CUSTOMER_CREATED",
            entityType = EntityType.CUSTOMER, entityId = "#result.id", entityName = "#result.title")
    public CustomerEntity saveCustomer(@RequestBody CustomerEntity customer, Authentication authentication) {
        if (customer.getId() == null) {
            dataScopeService.requireTenant(authentication, customer.getTenantId());
        } else {
            CustomerEntity existing = customerService.requireById(customer.getId());
            dataScopeService.requireTenant(authentication, existing.getTenantId());
        }
        return customerService.save(customer);
    }

    /**
     * 删除客户。
     */
    @DeleteMapping("/{customerId}")
    @ResponseStatus(HttpStatus.OK)
    @RequirePermission(resource = EntityType.CUSTOMER, operation = Operation.DELETE)
    @Audited(action = AuditActions.CUSTOMER_DELETED, entityType = EntityType.CUSTOMER,
            entityId = "#customerId")
    public void deleteCustomer(@PathVariable UUID customerId, Authentication authentication) {
        dataScopeService.requireCustomer(authentication, customerId);
        customerService.requireById(customerId);
        customerService.deleteById(customerId);
    }

    /**
     * 处理 当前路径 对应的接口请求。
     */
    @GetMapping
    @RequirePermission(resource = EntityType.CUSTOMER, operation = Operation.READ)
    public PageData<CustomerEntity> getCustomers(
            @RequestParam long pageSize,
            @RequestParam long page,
            @RequestParam(required = false) String textSearch,
            Authentication authentication) {
        SecurityUsers scope = dataScopeService.current(authentication);
        if (scope.getCustomerId() != null) {
            CustomerEntity customer = customerService.findById(scope.getCustomerId());
            return new PageData<>(customer == null ? List.of() : List.of(customer),
                    pageSize, page, customer == null ? 0 : 1);
        }
        return scope.getTenantId() == null
                ? customerService.findPage(pageSize, page, textSearch)
                : customerService.findPageByTenant(scope.getTenantId(), pageSize, page, textSearch);
    }
}
