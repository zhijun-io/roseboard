package com.roseboard.setting.oauth2.domain;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.roseboard.common.PageData;
import com.roseboard.setting.oauth2.client.OAuth2ClientEntity;
import com.roseboard.setting.oauth2.client.OAuth2ClientMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class DomainService {
    private static final Pattern UUID_IN_TEXT = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    private final DomainMapper mapper;
    private final OAuth2ClientMapper oauth2ClientMapper;

    public DomainService(DomainMapper mapper, OAuth2ClientMapper oauth2ClientMapper) {
        this.mapper = mapper;
        this.oauth2ClientMapper = oauth2ClientMapper;
    }

    @Transactional(rollbackFor = Exception.class)
    public DomainEntity save(DomainEntity domain, UUID[] oauth2ClientIds) {
        if (domain.getTenantId() == null) {
            domain.setTenantId(new UUID(0, 0));
        }
        if (domain.getId() == null) {
            domain.setId(UUID.randomUUID());
            domain.setCreatedTime(System.currentTimeMillis());
        } else if (mapper.selectById(domain.getId()) == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Domain not found");
        }
        if (oauth2ClientIds != null) {
            domain.setOauth2ClientIds(Arrays.toString(oauth2ClientIds));
        }
        mapper.insertOrUpdate(domain);
        return enrich(mapper.selectById(domain.getId()));
    }

    @Transactional(rollbackFor = Exception.class)
    public void updateOauth2Clients(UUID id, UUID[] clientIds) {
        DomainEntity domain = requireById(id);
        domain.setOauth2ClientIds(Arrays.toString(clientIds == null ? new UUID[0] : clientIds));
        mapper.updateById(domain);
    }

    public PageData<DomainEntity> findPage(long pageSize, long page, String textSearch) {
        Page<DomainEntity> result = mapper.selectPage(new Page<>(page + 1L, pageSize),
                new LambdaQueryWrapper<DomainEntity>()
                        .like(StringUtils.hasText(textSearch), DomainEntity::getName, textSearch)
                        .orderByAsc(DomainEntity::getName));
        result.getRecords().forEach(this::enrich);
        return new PageData<>(result.getRecords(), pageSize, page, result.getTotal());
    }

    public DomainEntity requireEnrichedById(UUID id) {
        return enrich(requireById(id));
    }

    @Transactional(rollbackFor = Exception.class)
    public void delete(UUID id) {
        mapper.deleteById(requireById(id).getId());
    }

    public DomainEntity requireById(UUID id) {
        DomainEntity domain = mapper.selectById(id);
        if (domain == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Domain not found");
        }
        return domain;
    }

    private DomainEntity enrich(DomainEntity domain) {
        if (domain == null || domain.getOauth2ClientIds() == null) {
            return domain;
        }
        List<OAuth2ClientEntity> infos = new ArrayList<>();
        Matcher matcher = UUID_IN_TEXT.matcher(domain.getOauth2ClientIds());
        while (matcher.find()) {
            OAuth2ClientEntity client = oauth2ClientMapper.selectById(UUID.fromString(matcher.group()));
            if (client != null) {
                client.setClientSecret(null);
                infos.add(client);
            }
        }
        domain.setOauth2ClientInfos(infos);
        return domain;
    }
}
