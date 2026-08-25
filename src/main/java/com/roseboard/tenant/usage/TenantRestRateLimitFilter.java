package com.roseboard.tenant.usage;

import com.roseboard.common.JacksonUtils;
import com.roseboard.infrastructure.security.api.SecurityUser;
import com.roseboard.infrastructure.security.jwt.SecurityRequestFilter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;

/**
 * Applies Profile REST rate-limit strings after authentication.
 */
public class TenantRestRateLimitFilter extends OncePerRequestFilter implements SecurityRequestFilter {
    private final TenantRateLimitService rateLimitService;

    public TenantRestRateLimitFilter(TenantRateLimitService rateLimitService) {
        this.rateLimitService = rateLimitService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof SecurityUser user
                && user.getTenantId() != null) {
            try {
                if ("TENANT_ADMIN".equals(user.getAuthorityName())) {
                    rateLimitService.consumeTenant(user.getTenantId(),
                            TenantRateLimitService.TENANT_SERVER_REST, 1);
                } else if ("CUSTOMER_USER".equals(user.getAuthorityName())) {
                    rateLimitService.consumeTenant(user.getTenantId(),
                            TenantRateLimitService.CUSTOMER_SERVER_REST, 1);
                }
            } catch (ResponseStatusException exception) {
                writeError(response, exception);
                return;
            }
        }
        filterChain.doFilter(request, response);
    }

    private static void writeError(HttpServletResponse response, ResponseStatusException exception)
            throws IOException {
        HttpStatus status = HttpStatus.resolve(exception.getStatusCode().value());
        if (status == null) {
            status = HttpStatus.TOO_MANY_REQUESTS;
        }
        response.setStatus(status.value());
        response.setContentType("application/json");
        response.getWriter().write(JacksonUtils.toString(
                exception.getReason() == null ? status.getReasonPhrase() : exception.getReason()));
    }
}
