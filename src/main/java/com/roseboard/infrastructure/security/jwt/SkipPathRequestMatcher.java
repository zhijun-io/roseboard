package com.roseboard.infrastructure.security.jwt;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.util.AntPathMatcher;
import org.springframework.util.Assert;

import java.util.List;
import java.util.stream.Collectors;

public class SkipPathRequestMatcher implements RequestMatcher {
    private final OrRequestMatcher matchers;
    private final RequestMatcher processingMatcher;

    public SkipPathRequestMatcher(List<String> pathsToSkip, String processingPath) {
        Assert.notNull(pathsToSkip, "List of paths to skip is required.");
        AntPathMatcher pathMatcher = new AntPathMatcher();
        List<RequestMatcher> m = pathsToSkip.stream()
                .map(path -> (RequestMatcher) request -> pathMatcher.match(path, request.getRequestURI()))
                .collect(Collectors.toList());
        matchers = new OrRequestMatcher(m);
        processingMatcher = request -> pathMatcher.match(processingPath, request.getRequestURI());
    }

    @Override
    public boolean matches(HttpServletRequest request) {
        if (matchers.matches(request)) {
            return false;
        }
        return processingMatcher.matches(request);
    }
}
