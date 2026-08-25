package com.roseboard.infrastructure.audit.servlet;

import com.roseboard.infrastructure.audit.AuditContext;
import com.roseboard.infrastructure.audit.context.AuditContextProvider;
import com.roseboard.infrastructure.audit.event.AuditOrigin;
import com.roseboard.infrastructure.web.RequestIdFilter;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import jakarta.servlet.http.HttpServletRequest;
import java.util.HashMap;
import java.util.Map;

/**
 * Servlet 环境适配器，只提取固定的低基数请求信息。
 * {@code remoteAddr} 使用 {@link HttpServletRequest#getRemoteAddr()}；反向代理后真实客户端 IP
 * 由 Spring {@code server.forward-headers-strategy}（如 {@code framework}）统一处理。
 */
public class ServletAuditContextProvider implements AuditContextProvider {

    @Override
    public AuditContext current() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (!(attributes instanceof ServletRequestAttributes servletAttributes)) {
            return AuditContext.empty();
        }
        HttpServletRequest request = servletAttributes.getRequest();
        Map<String, String> context = new HashMap<>(3);
        putIfPresent(context, "uri", request.getRequestURI());
        putIfPresent(context, "httpMethod", request.getMethod());
        putIfPresent(context, "remoteAddr", request.getRemoteAddr());
        return AuditContext.of(AuditOrigin.HTTP, requestId(request), context);
    }

    private static String requestId(HttpServletRequest request) {
        Object attribute = request.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE);
        return attribute instanceof String value && !value.isBlank() ? value : null;
    }

    private static void putIfPresent(Map<String, String> context, String key, String value) {
        if (value != null && !value.isBlank()) {
            context.put(key, value);
        }
    }
}
