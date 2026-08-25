package com.roseboard.infrastructure.security;

import com.roseboard.infrastructure.security.api.SecurityEventRecorder;
import com.roseboard.infrastructure.security.api.SecurityUser;
import com.roseboard.infrastructure.security.apikey.ApiKeyAuthenticationFilter;
import com.roseboard.infrastructure.security.apikey.ApiKeyAuthenticationProvider;
import com.roseboard.infrastructure.security.apikey.ApiKeySecurityUserProvider;
import com.roseboard.infrastructure.security.apikey.ApiKeyTokenResolver;
import com.roseboard.infrastructure.security.jwt.*;
import com.roseboard.infrastructure.security.jwt.mfa.MfaLoginPolicy;
import com.roseboard.infrastructure.security.oauth2.OAuth2AuthenticationFailureHandler;
import com.roseboard.infrastructure.security.oauth2.OAuth2LoginSuccessHandler;
import com.roseboard.infrastructure.web.PayloadSizeFilter;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.authentication.logout.HttpStatusReturningLogoutSuccessHandler;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

@AutoConfiguration
@EnableWebSecurity
@EnableMethodSecurity
@Order(100)
public class SecurityAutoConfiguration {
    public static final String AUTHORIZATION_HEADER = "Authorization";
    public static final String API_KEY_HEADER_PREFIX = "ApiKey ";
    public static final String BEARER_HEADER_PREFIX = "Bearer ";
    public static final String FORM_BASED_LOGIN_ENTRY_POINT = "/api/login";
    public static final String PUBLIC_LOGIN_ENTRY_POINT = "/api/login/public";
    public static final String TOKEN_REFRESH_ENTRY_POINT = "/api/token";
    public static final String DEVICE_API_ENTRY_POINT = "/api/http/**";
    public static final String TOKEN_BASED_AUTH_ENTRY_POINT = "/api/**";
    public static final String WS_ENTRY_POINT = "/api/ws/**";
    public static final String OAUTH2_LOGIN_PROCESSING_URL = "/login/oauth2/code/roseboard";
    public static final String MAIL_OAUTH2_PROCESSING_ENTRY_POINT =
            "/api/notifications/platform/channels/EMAIL/oauth2/code";
    public static final String DEVICE_CONNECTIVITY_CERTIFICATE_DOWNLOAD_ENTRY_POINT =
            "/api/device-connectivity/*/certificate/download";
    private static final String[] NON_TOKEN_ENTRY_POINTS = {
            "/index.html", "/assets/**", "/static/**", "/webjars/**", "/api/noauth/**",
            "/api/license/**", "/api/images/public/**", "/actuator/health", "/actuator/info", "/.well-known/**",
            "/swagger-ui.html", "/swagger-ui/**", "/v3/api-docs", "/v3/api-docs/**"
    };
    @Autowired(required = false)
    private OAuth2Properties oauth2Properties;
    @Value("${roseboard.security.oauth2.enabled:false}")
    private boolean oauth2Enabled;

    private SkipPathRequestMatcher buildSkipPathRequestMatcher() {
        List<String> pathsToSkip = new ArrayList<>(List.of(NON_TOKEN_ENTRY_POINTS));
        pathsToSkip.addAll(List.of(
                WS_ENTRY_POINT,
                TOKEN_REFRESH_ENTRY_POINT,
                FORM_BASED_LOGIN_ENTRY_POINT,
                PUBLIC_LOGIN_ENTRY_POINT,
                DEVICE_API_ENTRY_POINT,
                MAIL_OAUTH2_PROCESSING_ENTRY_POINT,
                DEVICE_CONNECTIVITY_CERTIFICATE_DOWNLOAD_ENTRY_POINT));
        return new SkipPathRequestMatcher(pathsToSkip, TOKEN_BASED_AUTH_ENTRY_POINT);
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    @ConditionalOnMissingBean
    public SecurityEventRecorder securityEventRecorder() {
        return new SecurityEventRecorder() {
            @Override
            public void recordLoginSuccess(SecurityUser principal) {
            }

            @Override
            public void recordLoginFailure(SecurityUser principal, String subject, String detail, String reason) {
            }

            @Override
            public void recordLogout(SecurityUser principal) {
            }
        };
    }
    @Bean
    public DaoAuthenticationProvider daoAuthenticationProvider(
            SecurityUserService identityProvider, PasswordEncoder passwordEncoder) {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider(identityProvider);
        provider.setPasswordEncoder(passwordEncoder);
        return provider;
    }

    @Bean
    public AuthenticationManager authenticationManager(
            DaoAuthenticationProvider daoAuthenticationProvider,
            JwtAuthenticationProvider jwtAuthenticationProvider,
            ApiKeyAuthenticationProvider apiKeyAuthenticationProvider,
            RefreshTokenAuthenticationProvider refreshTokenAuthenticationProvider) {
        return new ProviderManager(List.of(
                daoAuthenticationProvider,
                jwtAuthenticationProvider,
                apiKeyAuthenticationProvider,
                refreshTokenAuthenticationProvider));
    }

    @Bean
    public JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtGrantedAuthoritiesConverter authoritiesConverter = new JwtGrantedAuthoritiesConverter();
        authoritiesConverter.setAuthoritiesClaimName("authority");
        authoritiesConverter.setAuthorityPrefix("");
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setPrincipalClaimName("sub");
        converter.setJwtGrantedAuthoritiesConverter(authoritiesConverter);
        return converter;
    }
    @Bean
    public JwtAuthenticationSuccessHandler jwtAuthenticationSuccessHandler(
            JwtTokenFactory tokenService, MfaLoginPolicy mfaPolicy,
            ObjectMapper objectMapper,
            SecurityEventRecorder eventRecorder) {
        return new JwtAuthenticationSuccessHandler(tokenService, mfaPolicy, objectMapper, eventRecorder);
    }
    @Bean
    public JwtAuthenticationFailureHandler jwtAuthenticationFailureHandler(
            AuthenticationFailureRecorder recorder, SecurityErrorHandler errorHandler) {
        return new JwtAuthenticationFailureHandler(recorder, errorHandler);
    }

    @Bean
    public RestLoginAuthenticationFilter jsonLoginAuthenticationFilter(
            AuthenticationManager authenticationManager,
            JwtAuthenticationSuccessHandler successHandler,
            JwtAuthenticationFailureHandler failureHandler) {
        return new RestLoginAuthenticationFilter(FORM_BASED_LOGIN_ENTRY_POINT, authenticationManager,
                successHandler, failureHandler);
    }

    @Bean
    public RestLoginAuthenticationFilter publicLoginAuthenticationFilter(
            AuthenticationManager authenticationManager,
            JwtAuthenticationSuccessHandler successHandler,
            JwtAuthenticationFailureHandler failureHandler) {
        return new RestLoginAuthenticationFilter(PUBLIC_LOGIN_ENTRY_POINT, authenticationManager,
                successHandler, failureHandler);
    }
    @Bean
    public RefreshTokenAuthenticationFilter refreshTokenAuthenticationFilter(
            AuthenticationManager authenticationManager,
            JwtTokenFactory tokenService,
            ObjectMapper objectMapper, SecurityErrorHandler errorHandler) {
        return new RefreshTokenAuthenticationFilter(authenticationManager, tokenService, objectMapper,
                errorHandler);
    }
    @Bean
    public ApiKeyAuthenticationProvider apiKeyAuthenticationProvider(
            ApiKeySecurityUserProvider identityProvider) {
        return new ApiKeyAuthenticationProvider(identityProvider);
    }
    @Bean
    public RefreshTokenAuthenticationProvider refreshTokenAuthenticationProvider(
            JwtTokenFactory tokenService, SecurityUserService identityProvider) {
        return new RefreshTokenAuthenticationProvider(tokenService, identityProvider);
    }

    @Bean
    public OAuth2AuthenticationFailureHandler oauth2AuthenticationFailureHandler(
            SecurityEventRecorder eventRecorder, SecurityErrorHandler errorHandler) {
        return new OAuth2AuthenticationFailureHandler(eventRecorder, errorHandler);
    }
    @Bean
    public ApiKeyAuthenticationFilter apiKeyAuthenticationFilter(
            AuthenticationManager authenticationManager,
            JwtAuthenticationFailureHandler failureHandler) {
        ApiKeyTokenResolver bearerTokenExtractor = new ApiKeyTokenResolver();

        ApiKeyAuthenticationFilter filter = new ApiKeyAuthenticationFilter(
                failureHandler,
                bearerTokenExtractor,
                request -> buildSkipPathRequestMatcher().matches(request)
                        && bearerTokenExtractor.resolve(request) != null);
        filter.setAuthenticationManager(authenticationManager);
        return filter;
    }
    @Bean
    public JwtAuthenticationFilter jwtAuthenticationFilter(
            AuthenticationManager authenticationManager,
            JwtAuthenticationFailureHandler failureHandler) {
        DefaultBearerTokenResolver bearerTokenExtractor = new DefaultBearerTokenResolver();

        JwtAuthenticationFilter filter = new JwtAuthenticationFilter(
                failureHandler,
                bearerTokenExtractor,
                buildSkipPathRequestMatcher());
        filter.setAuthenticationManager(authenticationManager);
        return filter;
    }
    @Bean
    @Order(0)
    public SecurityFilterChain staticResources(HttpSecurity http,
                                               SecurityHeadersConfigurer headersConfigurer) throws Exception {
        http.securityMatchers(matchers -> matchers
                        .requestMatchers("/*.js", "/*.css", "/*.ico", "/assets/**", "/static/**", "/webjars/**"))
                .headers(headers -> {
                    headers.defaultsDisabled().cacheControl(config -> {});
                    headersConfigurer.customize(headers);
                })
                .authorizeHttpRequests(authorize -> authorize.anyRequest().permitAll())
                .requestCache(requestCache -> requestCache.disable())
                .securityContext(securityContext -> securityContext.disable())
                .sessionManagement(session -> session.disable());
        return http.build();
    }

    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            JwtAuthenticationFilter jwtAuthenticationFilter,
            @Qualifier("jsonLoginAuthenticationFilter") RestLoginAuthenticationFilter restLoginAuthenticationFilter,
            @Qualifier("publicLoginAuthenticationFilter") RestLoginAuthenticationFilter publicLoginAuthenticationFilter,
            RefreshTokenAuthenticationFilter refreshTokenAuthenticationFilter,
            ApiKeyAuthenticationFilter apiKeyAuthenticationFilter,
            OAuth2AuthenticationFailureHandler oauth2FailureHandler,
            SecurityErrorHandler errorHandler,
            SecurityHeadersConfigurer headersConfigurer,
            PayloadSizeFilter payloadSizeFilter,
            JwtLogoutHandler logoutHandler,
            ObjectProvider<ClientRegistrationRepository> clientRegistrations,
            ObjectProvider<OAuth2LoginSuccessHandler> oauth2SuccessHandlers,
            ObjectProvider<SecurityRequestFilter> requestFilters) throws Exception {
        http.headers(headers -> {
                    headers.defaultsDisabled().cacheControl(config -> {});
                    headersConfigurer.customize(headers);
                })
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(
                        oauth2Enabled ? SessionCreationPolicy.IF_REQUIRED : SessionCreationPolicy.STATELESS))
                .cors(cors -> {})
                .exceptionHandling(exception -> exception
                        .authenticationEntryPoint(errorHandler)
                        .accessDeniedHandler(errorHandler))
                .logout(logout -> logout
                        .logoutUrl("/api/logout")
                        .addLogoutHandler(logoutHandler)
                        .logoutSuccessHandler(new HttpStatusReturningLogoutSuccessHandler(HttpStatus.OK)))
                .authorizeHttpRequests(authorize -> {
                    authorize.requestMatchers(NON_TOKEN_ENTRY_POINTS).permitAll();
                    authorize.requestMatchers(FORM_BASED_LOGIN_ENTRY_POINT, PUBLIC_LOGIN_ENTRY_POINT, TOKEN_REFRESH_ENTRY_POINT, MAIL_OAUTH2_PROCESSING_ENTRY_POINT, DEVICE_CONNECTIVITY_CERTIFICATE_DOWNLOAD_ENTRY_POINT, WS_ENTRY_POINT, DEVICE_API_ENTRY_POINT, "/oauth2/authorization/**", OAUTH2_LOGIN_PROCESSING_URL).permitAll();
                    authorize.requestMatchers(TOKEN_BASED_AUTH_ENTRY_POINT).authenticated();
                    authorize.anyRequest().permitAll();
                })
                .addFilterBefore(restLoginAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(publicLoginAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(apiKeyAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(refreshTokenAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(payloadSizeFilter, UsernamePasswordAuthenticationFilter.class);
        requestFilters.orderedStream().forEach(filter ->
                http.addFilterAfter(filter, JwtAuthenticationFilter.class));
        ClientRegistrationRepository registrationRepository = clientRegistrations.getIfAvailable();
        OAuth2LoginSuccessHandler oauth2SuccessHandler = oauth2SuccessHandlers.getIfAvailable();
        if (oauth2Properties != null && registrationRepository != null && oauth2SuccessHandler != null) {
            http.oauth2Login(login -> login
                    .loginProcessingUrl(oauth2Properties.getLoginProcessingUrl() == null
                            ? OAUTH2_LOGIN_PROCESSING_URL : oauth2Properties.getLoginProcessingUrl())
                    .successHandler(oauth2SuccessHandler)
                    .failureHandler(oauth2FailureHandler));
        }
        return http.build();
    }
}
