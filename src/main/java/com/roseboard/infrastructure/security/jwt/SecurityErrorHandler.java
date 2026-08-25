package com.roseboard.infrastructure.security.jwt;

import com.roseboard.common.JacksonUtils;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Component
public class SecurityErrorHandler implements AuthenticationEntryPoint, AccessDeniedHandler {

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                         AuthenticationException exception) throws IOException, ServletException {
        write(response, HttpServletResponse.SC_UNAUTHORIZED, "AUTHENTICATION_FAILED", "Authentication failed");
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
                       AccessDeniedException exception) throws IOException, ServletException {
        write(response, HttpServletResponse.SC_FORBIDDEN, "PERMISSION_DENIED", "Access denied");
    }

    private void write(HttpServletResponse response, int status, String code, String message)
            throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        JacksonUtils.writeValue(response.getOutputStream(),new ErrorResponse(status, code, message));
    }

    private record ErrorResponse(int status, String error, String message) {
    }
}
