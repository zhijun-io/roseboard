package com.roseboard.setting.oauth2;

import com.roseboard.setting.oauth2.client.OAuth2ClientEntity;
import com.roseboard.setting.oauth2.client.OAuth2ClientMapper;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;

import java.util.List;
import java.util.UUID;

public class MybatisClientRegistrationRepository implements ClientRegistrationRepository {
    private final OAuth2ClientMapper mapper;

    public MybatisClientRegistrationRepository(OAuth2ClientMapper mapper) { this.mapper = mapper; }

    @Override
    public ClientRegistration findByRegistrationId(String registrationId) {
        UUID clientId;
        try {
            clientId = UUID.fromString(registrationId);
        } catch (IllegalArgumentException exception) {
            return null;
        }
        OAuth2ClientEntity client = mapper.selectById(clientId);
        if (client == null || client.getAuthorizationUri() == null || client.getTokenUri() == null) return null;
        return ClientRegistration.withRegistrationId(registrationId)
                .clientId(client.getClientId()).clientSecret(client.getClientSecret())
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .authorizationUri(client.getAuthorizationUri()).tokenUri(client.getTokenUri())
                .userInfoUri(client.getUserInfoUri())
                .jwkSetUri(client.getJwkSetUri())
                .redirectUri(client.getRedirectUri() == null ? "{baseUrl}/login/oauth2/code/{registrationId}" : client.getRedirectUri())
                .build();
    }

    private List<String> scopes(String value) {
        return value == null || value.isBlank() ? List.of("openid", "profile", "email") : List.of(value.trim().split("\\s+"));
    }
}
