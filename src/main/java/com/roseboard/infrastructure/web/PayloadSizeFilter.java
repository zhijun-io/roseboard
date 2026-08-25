package com.roseboard.infrastructure.web;

import com.roseboard.common.JacksonUtils;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

public class PayloadSizeFilter extends OncePerRequestFilter {
    private final Map<String, Long> limits = new LinkedHashMap<>();
    private final AntPathMatcher pathMatcher = new AntPathMatcher();

    public PayloadSizeFilter(String limitsConfiguration) {
        for (String limit : limitsConfiguration.split(";")) {
            try {
                String urlPathPattern = limit.split("=")[0];
                long maxPayloadSize = Long.parseLong(limit.split("=")[1]);
                limits.put(urlPathPattern, maxPayloadSize);
            } catch (Exception exception) {
                throw new IllegalArgumentException("Failed to parse size limits configuration: " + limitsConfiguration, exception);
            }
        }
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws IOException, ServletException {
        for (String url : limits.keySet()) {
            if (pathMatcher.match(url, request.getRequestURI())) {
                if (checkMaxPayloadSizeExceeded(request, response, limits.get(url))) {
                    return;
                }
                break;
            }
        }
        chain.doFilter(request, response);
    }

    private boolean checkMaxPayloadSizeExceeded(HttpServletRequest request, HttpServletResponse response,
                                                long maxPayloadSize) throws IOException {
        if (request.getContentLength() > maxPayloadSize) {
            handleMaxPayloadSizeExceededException(response, new MaxPayloadSizeExceededException(maxPayloadSize));
            return true;
        }
        return false;
    }

    @Override
    protected boolean shouldNotFilterAsyncDispatch() {
        return false;
    }

    @Override
    protected boolean shouldNotFilterErrorDispatch() {
        return false;
    }

    private void handleMaxPayloadSizeExceededException(HttpServletResponse response,
                                                        MaxPayloadSizeExceededException exception) throws IOException {
        response.setStatus(HttpStatus.PAYLOAD_TOO_LARGE.value());
        response.setContentType("application/json");
        response.getWriter().write(JacksonUtils.toString(exception.getMessage()));
    }

    private record MaxPayloadSizeExceededException(long maxPayloadSize) {
        public String getMessage() {
            return "Payload size exceeds the limit of " + maxPayloadSize + " bytes";
        }
    }
}
