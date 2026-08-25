package com.roseboard.notification;

import com.roseboard.infrastructure.audit.event.EntityType;
import com.roseboard.infrastructure.security.api.RequirePermission;
import com.roseboard.infrastructure.security.api.Operation;
import com.roseboard.notification.channel.ChannelConfigService;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;

/**
 * 平台邮件 OAuth2 接口：生成授权地址并处理邮件账户回调。
 */
@RestController
@RequestMapping("/api/notifications/platform/channels/email/oauth2")
public class MailOAuth2PlatformController {
    private static final String STATE_COOKIE = "ROSEBOARD_MAIL_OAUTH2_STATE";
    private static final String PREVIOUS_URI_COOKIE = "ROSEBOARD_MAIL_OAUTH2_PREV_URI";
    private static final String DEFAULT_PREVIOUS_URI = "/settings/outgoing-mail";
    private static final int COOKIE_MAX_AGE_SECONDS = 180;

    private final ChannelConfigService channelConfigService;
    private final SecureRandom secureRandom = new SecureRandom();

    public MailOAuth2PlatformController(ChannelConfigService channelConfigService) {
        this.channelConfigService = channelConfigService;
    }

    /**
     * 查询 OAuth2 登录回调地址。
     */
    @GetMapping("/login-processing-url")
    @RequirePermission(resource = EntityType.ADMIN_SETTINGS, operation = Operation.READ)
    public String loginProcessingUrl() {
        return "\"/api/notifications/platform/channels/email/oauth2/code\"";
    }

    /**
     * 生成 OAuth2 授权跳转。
     */
    @GetMapping(value = "/authorize", produces = MediaType.TEXT_PLAIN_VALUE)
    @RequirePermission(resource = EntityType.ADMIN_SETTINGS, operation = Operation.READ)
    public String authorize(HttpServletRequest request, HttpServletResponse response) {
        String state = newState();
        addCookie(response, STATE_COOKIE, state, request.isSecure());
        addCookie(response, PREVIOUS_URI_COOKIE, safePreviousUri(request.getParameter("prevUri")), request.isSecure());

        ObjectNode settings = channelConfigService.platformOAuthSettings();
        String clientId = required(settings, "clientId");
        String authorizationUri = required(settings, "authUri");
        String redirectUri = required(settings, "redirectUri");
        return UriComponentsBuilder.fromUriString(authorizationUri)
                .queryParam("response_type", "code")
                .queryParam("client_id", clientId)
                .queryParam("redirect_uri", redirectUri)
                .queryParam("scope", scope(settings.get("scope")))
                .queryParam("state", state)
                .build()
                .encode()
                .toUriString();
    }

    /**
     * 处理 OAuth2 回调并完成登录。
     */
    @GetMapping(value = "/code", params = {"code", "state"})
    public void callback(@RequestParam String code,
                         @RequestParam String state,
                         HttpServletRequest request,
                         HttpServletResponse response) throws IOException {
        Cookie stateCookie = cookie(request, STATE_COOKIE);
        if (stateCookie == null || !stateCookie.getValue().equals(state)) {
            clearCookie(response, STATE_COOKIE, request.isSecure());
            clearCookie(response, PREVIOUS_URI_COOKIE, request.isSecure());
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid mail OAuth2 state");
        }

        ObjectNode settings = channelConfigService.platformOAuthSettings();
        Map<?, ?> tokenResponse = requestToken(
                required(settings, "tokenUri"),
                required(settings, "clientId"),
                required(settings, "clientSecret"),
                required(settings, "redirectUri"),
                code);
        Object accessToken = tokenResponse.get("access_token");
        Object refreshToken = tokenResponse.get("refresh_token");
        if (accessToken == null && refreshToken == null) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "Mail OAuth2 provider did not return a token");
        }

        ObjectNode updated = settings.deepCopy();
        if (refreshToken != null) {
            updated.put("refreshToken", refreshToken.toString());
        }
        updated.put("tokenGenerated", true);
        channelConfigService.savePlatformOAuthSettings(updated);

        String previousUri = safePreviousUri(cookieValue(request, PREVIOUS_URI_COOKIE));
        clearCookie(response, STATE_COOKIE, request.isSecure());
        clearCookie(response, PREVIOUS_URI_COOKIE, request.isSecure());
        response.sendRedirect(previousUri);
    }

    private Map<?, ?> requestToken(String tokenUri, String clientId, String clientSecret,
                                   String redirectUri, String code) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "authorization_code");
        form.add("code", code);
        form.add("client_id", clientId);
        form.add("client_secret", clientSecret);
        form.add("redirect_uri", redirectUri);
        try {
            Map<?, ?> result = RestClient.create().post()
                    .uri(tokenUri)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .body(Map.class);
            return result == null ? Map.of() : result;
        } catch (Exception exception) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "Unable to retrieve mail OAuth2 token", exception);
        }
    }

    private String required(ObjectNode settings, String field) {
        JsonNode value = settings.get(field);
        if (value == null || value.isNull() || value.asText("").isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Mail OAuth2 setting is required: " + field);
        }
        return value.asText();
    }

    private String scope(JsonNode value) {
        if (value == null || value.isNull()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Mail OAuth2 setting is required: scope");
        }
        if (value.isArray()) {
            StringBuilder joined = new StringBuilder();
            for (JsonNode item : value) {
                if (item.isTextual() && !item.asText().isBlank()) {
                    if (!joined.isEmpty()) {
                        joined.append(' ');
                    }
                    joined.append(item.asText());
                }
            }
            if (!joined.isEmpty()) {
                return joined.toString();
            }
        }
        if (value.isTextual() && !value.asText().isBlank()) {
            return value.asText();
        }
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Mail OAuth2 setting is required: scope");
    }

    private String newState() {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private void addCookie(HttpServletResponse response, String name, String value, boolean secure) {
        Cookie cookie = new Cookie(name, value);
        cookie.setHttpOnly(true);
        cookie.setSecure(secure);
        cookie.setPath("/");
        cookie.setMaxAge(COOKIE_MAX_AGE_SECONDS);
        response.addCookie(cookie);
    }

    private void clearCookie(HttpServletResponse response, String name, boolean secure) {
        Cookie cookie = new Cookie(name, "");
        cookie.setHttpOnly(true);
        cookie.setSecure(secure);
        cookie.setPath("/");
        cookie.setMaxAge(0);
        response.addCookie(cookie);
    }

    private Cookie cookie(HttpServletRequest request, String name) {
        if (request.getCookies() == null) {
            return null;
        }
        for (Cookie item : request.getCookies()) {
            if (name.equals(item.getName())) {
                return item;
            }
        }
        return null;
    }

    private String cookieValue(HttpServletRequest request, String name) {
        Cookie item = cookie(request, name);
        return item == null ? null : item.getValue();
    }

    private String safePreviousUri(String value) {
        return value != null && value.startsWith("/") && !value.startsWith("//")
                ? value : DEFAULT_PREVIOUS_URI;
    }
}
