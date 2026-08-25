package com.roseboard.user;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;
import java.util.UUID;

@Mapper
public interface UserMapper extends BaseMapper<UserEntity> {
    default UserEntity findByEmail(String email) {
        return selectOne(new LambdaQueryWrapper<UserEntity>()
                .apply("LOWER(email) = LOWER({0})", email));
    }

    default Page<UserEntity> selectPage(long pageSize, long page, String textSearch) {
        return selectPage(new Page<>(page + 1L, pageSize), baseQuery(textSearch));
    }

    default Page<UserEntity> selectPageByTenant(UUID tenantId, long pageSize, long page, String textSearch) {
        return selectPage(new Page<>(page + 1L, pageSize),
                baseQuery(textSearch).eq(UserEntity::getTenantId, tenantId));
    }

    default Page<UserEntity> selectPageByCustomer(UUID customerId, long pageSize, long page, String textSearch) {
        return selectPage(new Page<>(page + 1L, pageSize),
                baseQuery(textSearch).eq(UserEntity::getCustomerId, customerId));
    }

    default Page<UserEntity> selectPageUserInfos(UUID tenantId, UUID customerId,
                                                  long pageSize, long page, String textSearch) {
        return selectPage(new Page<>(page + 1L, pageSize),
                infoQuery(tenantId, customerId, textSearch));
    }
    default List<UserEntity> selectUserInfos(UUID tenantId, UUID customerId, String textSearch) {
        return selectList(infoQuery(tenantId, customerId, textSearch));
    }

    private static LambdaQueryWrapper<UserEntity> infoQuery(UUID tenantId, UUID customerId,
                                                            String textSearch) {
        return new LambdaQueryWrapper<UserEntity>()
                .eq(tenantId != null, UserEntity::getTenantId, tenantId)
                .eq(customerId != null, UserEntity::getCustomerId, customerId)
                .and(textSearch != null && !textSearch.isBlank(), query -> query
                        .like(UserEntity::getEmail, textSearch)
                        .or().like(UserEntity::getFirstName, textSearch)
                        .or().like(UserEntity::getLastName, textSearch))
                .orderByAsc(UserEntity::getEmail);
    }

    private static LambdaQueryWrapper<UserEntity> baseQuery(String textSearch) {
        return new LambdaQueryWrapper<UserEntity>()
                .like(textSearch != null && !textSearch.isBlank(), UserEntity::getEmail, textSearch)
                .orderByAsc(UserEntity::getEmail);
    }
}
