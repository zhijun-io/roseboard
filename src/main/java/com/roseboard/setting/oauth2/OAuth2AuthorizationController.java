package com.roseboard.setting.oauth2;

import com.roseboard.infrastructure.security.jwt.JwtTokenFactory;
import com.roseboard.setting.oauth2.client.OAuth2ClientEntity;
import com.roseboard.setting.oauth2.client.OAuth2ClientMapper;
import com.roseboard.setting.security.DefaultSecurityUserService;
import com.roseboard.infrastructure.security.api.SecurityUser;
import com.roseboard.user.UserEntity;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;

/**
 * OAuth2 授权接口：处理 OAuth2 授权跳转和回调登录。
 */
@RestController
public class OAuth2AuthorizationController {
    private final OAuth2ClientMapper mapper;
    private final OAuth2StateService stateService;
    private final OAuth2UserMapper userMapper;
    private final DefaultSecurityUserService userDetailsService;
    private final JwtTokenFactory tokenService;
    private final boolean enabled;

    public OAuth2AuthorizationController(OAuth2ClientMapper mapper, OAuth2StateService stateService,
                                          OAuth2UserMapper userMapper,
                                          DefaultSecurityUserService userDetailsService,
                                          JwtTokenFactory tokenService,
                                          @Value("${roseboard.security.oauth2.enabled:false}") boolean enabled) {
        this.mapper = mapper;
        this.stateService = stateService;
        this.userMapper = userMapper;
        this.userDetailsService = userDetailsService;
        this.tokenService = tokenService;
        this.enabled = enabled;
    }

    /**
     * 查询 OAuth2 登录回调地址。
     */
    @GetMapping("/api/oauth2/loginProcessingUrl")
    public String loginProcessingUrl() {
        return "\"/login/oauth2/code/*\"";
    }

    /**
     * 生成 OAuth2 授权跳转。
     */
    @GetMapping("/oauth2/authorization/{clientId}")
    public void authorize(@PathVariable UUID clientId, HttpServletResponse response) throws IOException {
        requireEnabled();
        OAuth2ClientEntity client = mapper.selectById(clientId);
        if (client == null || client.getAuthorizationUri() == null || client.getClientId() == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "OAuth2 client not found");
        }
        String redirectUri = redirectUri(client);
        String redirect = UriComponentsBuilder.fromUriString(client.getAuthorizationUri())
                .queryParam("response_type", "code").queryParam("client_id", client.getClientId())
                .queryParam("redirect_uri", redirectUri)
                .queryParam("scope", client.getScope() == null ? "openid profile email" : client.getScope())
                .queryParam("state", stateService.create(clientId)).build().encode().toUriString();
        response.sendRedirect(redirect);
    }

    /**
     * 处理 OAuth2 回调并完成登录。
     */
    @GetMapping("/login/oauth2/code/roseboard")
    public Object callback(@RequestParam String code, @RequestParam String state) {
        requireEnabled();
        UUID clientId = stateService.consume(state);
        if (clientId == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid OAuth2 state");
        OAuth2ClientEntity client = mapper.selectById(clientId);
        if (client == null || client.getTokenUri() == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "OAuth2 client not found");
        String form = "grant_type=authorization_code&code=" + enc(code) + "&client_id=" + enc(client.getClientId())
                + "&client_secret=" + enc(client.getClientSecret()) + "&redirect_uri=" + enc(redirectUri(client));
        Map<String, Object> providerToken = RestClient.create().post().uri(client.getTokenUri())
                .contentType(MediaType.APPLICATION_FORM_URLENCODED).body(form)
                .retrieve().body(Map.class);
        if (providerToken == null || providerToken.get("access_token") == null || client.getUserInfoUri() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "OAuth2 provider did not return a usable identity token");
        }
        Map<String, Object> userInfo = RestClient.create().get().uri(client.getUserInfoUri())
                .headers(headers -> headers.setBearerAuth(providerToken.get("access_token").toString()))
                .retrieve().body(Map.class);
        UserEntity existing = userMapper.findExisting(userInfo, client);
        if (existing == null) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "OAuth2 user is not registered");
        }
        UserDetails details = userDetailsService.loadUserByUsername(existing.getEmail());
        return tokenService.login((SecurityUser) details);
    }
    private String redirectUri(OAuth2ClientEntity client) { return client.getRedirectUri() == null ? "/login/oauth2/code/roseboard" : client.getRedirectUri(); }
    private void requireEnabled() { if (!enabled) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "OAuth2 is disabled"); }
    private String enc(String value) { return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8); }
}
