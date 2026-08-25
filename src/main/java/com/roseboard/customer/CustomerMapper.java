package com.roseboard.customer;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.apache.ibatis.annotations.Mapper;

import java.util.UUID;

@Mapper
public interface CustomerMapper extends BaseMapper<CustomerEntity> {
    default Page<CustomerEntity> selectPage(long pageSize, long page, String textSearch) {
        return selectPage(new Page<>(page + 1L, pageSize), baseQuery(textSearch));
    }

    default Page<CustomerEntity> selectPageByTenant(UUID tenantId, long pageSize, long page,
                                                     String textSearch) {
        return selectPage(new Page<>(page + 1L, pageSize),
                baseQuery(textSearch).eq(CustomerEntity::getTenantId, tenantId));
    }

    private static LambdaQueryWrapper<CustomerEntity> baseQuery(String textSearch) {
        return new LambdaQueryWrapper<CustomerEntity>()
                .like(textSearch != null && !textSearch.isBlank(), CustomerEntity::getTitle, textSearch)
                .orderByAsc(CustomerEntity::getTitle);
    }
}
