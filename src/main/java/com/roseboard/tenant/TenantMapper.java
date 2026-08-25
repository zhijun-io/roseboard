package com.roseboard.tenant;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.apache.ibatis.annotations.Mapper;

import java.util.UUID;

@Mapper
public interface TenantMapper extends BaseMapper<TenantEntity> {
    default Page<TenantEntity> selectPage(long pageSize, long page, String textSearch) {
        return selectPage(new Page<>(page + 1L, pageSize),
                new LambdaQueryWrapper<TenantEntity>()
                        .like(textSearch != null && !textSearch.isBlank(), TenantEntity::getTitle, textSearch)
                        .orderByAsc(TenantEntity::getTitle));
    }

    default Page<TenantEntity> selectPageById(UUID tenantId, long pageSize, long page, String textSearch) {
        return selectPage(new Page<>(page + 1L, pageSize),
                new LambdaQueryWrapper<TenantEntity>()
                        .eq(TenantEntity::getId, tenantId)
                        .like(textSearch != null && !textSearch.isBlank(), TenantEntity::getTitle, textSearch)
                        .orderByAsc(TenantEntity::getTitle));
    }
}
