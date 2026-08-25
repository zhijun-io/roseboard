package com.roseboard.infrastructure.security.jwt;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;

import static org.junit.jupiter.api.Assertions.assertFalse;

class SecurityErrorHandlerTest {

    private final SecurityErrorHandler handler = new SecurityErrorHandler();
    private final HttpServletRequest request = new org.springframework.mock.web.MockHttpServletRequest();

    @Test
    void authenticationResponseDoesNotExposeExceptionMessage() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler.commence(request, response, new BadCredentialsException("internal database detail"));

        assertFalse(response.getContentAsString().contains("internal database detail"));
    }

    @Test
    void accessDeniedResponseDoesNotExposeExceptionMessage() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler.handle(request, response, new AccessDeniedException("internal authorization detail"));

        assertFalse(response.getContentAsString().contains("internal authorization detail"));
    }
}
