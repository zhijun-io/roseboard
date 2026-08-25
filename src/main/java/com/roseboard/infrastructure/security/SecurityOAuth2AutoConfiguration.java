package com.roseboard.infrastructure.security;

import com.roseboard.setting.oauth2.DefaultOAuth2SuccessHandler;
import com.roseboard.infrastructure.security.api.SecurityEventRecorder;
import com.roseboard.infrastructure.security.jwt.JwtTokenFactory;
import com.roseboard.infrastructure.security.jwt.mfa.MfaLoginPolicy;
import com.roseboard.infrastructure.security.oauth2.OAuth2LoginSuccessHandler;
import com.roseboard.infrastructure.security.oauth2.OAuth2SecurityUserProvider;
import com.roseboard.setting.oauth2.client.OAuth2ClientMapper;
import com.roseboard.setting.oauth2.MybatisClientRegistrationRepository;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;

@AutoConfiguration
public class SecurityOAuth2AutoConfiguration {
    @Bean
    @ConditionalOnProperty(name = "roseboard.security.oauth2.enabled", havingValue = "true")
    ClientRegistrationRepository clientRegistrationRepository(OAuth2ClientMapper mapper) {
        return new MybatisClientRegistrationRepository(mapper);
    }

    @Bean
    OAuth2LoginSuccessHandler oauth2LoginSuccessHandler(
            OAuth2SecurityUserProvider identityProvider,
            MfaLoginPolicy mfaPolicy,
            JwtTokenFactory tokenService,
            SecurityEventRecorder eventRecorder) {
        return new DefaultOAuth2SuccessHandler(identityProvider, mfaPolicy, tokenService,
                 eventRecorder);
    }

}
