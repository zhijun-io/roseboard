package com.roseboard.tenant.profile;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.roseboard.common.PageData;
import com.roseboard.tenant.TenantEntity;
import com.roseboard.tenant.TenantMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

@Service
public class TenantProfileService {
    private final TenantProfileMapper mapper;
    private final TenantMapper tenantMapper;

    public TenantProfileService(TenantProfileMapper mapper, TenantMapper tenantMapper) {
        this.mapper = mapper;
        this.tenantMapper = tenantMapper;
    }

    public TenantProfileEntity findById(UUID id) {
        return mapper.selectById(id);
    }

    public TenantProfileEntity requireById(UUID id) {
        TenantProfileEntity profile = mapper.selectById(id);
        if (profile == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Tenant profile not found");
        }
        return profile;
    }

    public List<TenantProfileEntity> findByIds(Collection<UUID> ids) {
        return mapper.selectBatchIds(ids);
    }

    public TenantProfileEntity findDefault() {
        return mapper.selectOne(new LambdaQueryWrapper<TenantProfileEntity>()
                .eq(TenantProfileEntity::getIsDefault, true).last("limit 1"));
    }

    public TenantProfileEntity requireDefault() {
        TenantProfileEntity profile = findDefault();
        if (profile == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Default tenant profile not found");
        }
        return profile;
    }

    @Transactional(rollbackFor = Exception.class)
    public TenantProfileEntity save(TenantProfileEntity profile) {
        profile.setProfileData(TenantProfileData.normalize(profile.getProfileData()));
        if (profile.getIsolatedTbCore() == null) {
            profile.setIsolatedTbCore(false);
        }
        if (profile.getIsolatedTbRuleEngine() == null) {
            profile.setIsolatedTbRuleEngine(false);
        }
        if (profile.getId() == null) {
            profile.setId(UUID.randomUUID());
            profile.setCreatedTime(System.currentTimeMillis());
            if (profile.getIsDefault() == null) {
                profile.setIsDefault(false);
            }
            mapper.insert(profile);
        } else {
            TenantProfileEntity existing = mapper.selectById(profile.getId());
            if (existing == null) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Tenant profile not found");
            }
            if (profile.getIsDefault() == null) {
                profile.setIsDefault(existing.getIsDefault());
            }
            if (profile.getCreatedTime() == null) {
                profile.setCreatedTime(existing.getCreatedTime());
            }
            mapper.updateById(profile);
        }
        if (Boolean.TRUE.equals(profile.getIsDefault())) {
            mapper.update(null, new LambdaUpdateWrapper<TenantProfileEntity>()
                    .set(TenantProfileEntity::getIsDefault, false)
                    .ne(TenantProfileEntity::getId, profile.getId()));
        }
        return mapper.selectById(profile.getId());
    }

    @Transactional(rollbackFor = Exception.class)
    public TenantProfileEntity setDefault(UUID id) {
        TenantProfileEntity profile = requireById(id);
        mapper.update(null, new LambdaUpdateWrapper<TenantProfileEntity>()
                .set(TenantProfileEntity::getIsDefault, false));
        profile.setIsDefault(true);
        mapper.updateById(profile);
        return mapper.selectById(id);
    }

    @Transactional(rollbackFor = Exception.class)
    public void delete(UUID id) {
        TenantProfileEntity profile = mapper.selectById(id);
        if (profile == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Tenant profile not found");
        }
        if (Boolean.TRUE.equals(profile.getIsDefault())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Deletion of Default Tenant Profile is prohibited!");
        }
        long refs = tenantMapper.selectCount(new LambdaQueryWrapper<TenantEntity>()
                .eq(TenantEntity::getTenantProfileId, id));
        if (refs > 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "The tenant profile referenced by the tenants cannot be deleted!");
        }
        mapper.deleteById(id);
    }

    public PageData<TenantProfileEntity> findPage(long pageSize, long page, String textSearch) {
        Page<TenantProfileEntity> result = mapper.selectPage(pageSize, page, textSearch);
        return new PageData<>(result.getRecords(), pageSize, page, result.getTotal());
    }
}
