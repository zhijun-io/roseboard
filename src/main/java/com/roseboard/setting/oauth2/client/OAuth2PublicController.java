package com.roseboard.setting.oauth2.client;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * OAuth2 公共信息接口：返回登录所需的公开 Client 信息。
 */
@RestController
@RequestMapping("/api/noauth")
public class OAuth2PublicController {
    private final OAuth2ClientMapper mapper;
    private final boolean enabled;

    public OAuth2PublicController(OAuth2ClientMapper mapper,
                                  @Value("${roseboard.security.oauth2.enabled:false}") boolean enabled) {
        this.mapper = mapper;
        this.enabled = enabled;
    }

    @RequestMapping(value = "/oauth2Clients", method = {RequestMethod.GET, RequestMethod.POST})
    public List<OAuth2ClientInfo> clients() {
        if (!enabled) return List.of();
        return mapper.selectList(new LambdaQueryWrapper<OAuth2ClientEntity>()
                        .eq(OAuth2ClientEntity::getActivateUser, true))
                .stream()
                .filter(client -> Boolean.TRUE.equals(client.getActivateUser()))
                .map(client -> new OAuth2ClientInfo(
                        client.getTitle(),
                        null,
                        "/oauth2/authorization/" + client.getId()))
                .toList();
    }
}
