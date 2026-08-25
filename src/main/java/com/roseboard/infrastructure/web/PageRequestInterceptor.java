package com.roseboard.infrastructure.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/** Rejects unbounded or invalid pagination before it reaches a mapper. */
@Component
public class PageRequestInterceptor implements HandlerInterceptor {
    static final long MAX_PAGE_SIZE = 1_000;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        String pageSize = request.getParameter("pageSize");
        String page = request.getParameter("page");
        if (pageSize == null && page == null) {
            return true;
        }
        try {
            long parsedPageSize = pageSize == null ? 10 : Long.parseLong(pageSize);
            long parsedPage = page == null ? 0 : Long.parseLong(page);
            if (parsedPageSize < 1 || parsedPageSize > MAX_PAGE_SIZE || parsedPage < 0) {
                response.sendError(HttpServletResponse.SC_BAD_REQUEST, "Invalid page request");
                return false;
            }
        } catch (NumberFormatException exception) {
            response.sendError(HttpServletResponse.SC_BAD_REQUEST, "Invalid page request");
            return false;
        }
        return true;
    }
}
