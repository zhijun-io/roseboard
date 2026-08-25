package com.roseboard.infrastructure.websocket;

import com.roseboard.infrastructure.security.api.SecurityUser;

import java.net.InetSocketAddress;

public class WebSocketSessionRef {
    private final String sessionId;
    private SecurityUser securityUser;
    private final InetSocketAddress localAddress;
    private final InetSocketAddress remoteAddress;
    private final WebSocketSessionType sessionType;

    public WebSocketSessionRef(String sessionId,
                               SecurityUser securityUser,
                               InetSocketAddress localAddress,
                               InetSocketAddress remoteAddress,
                               WebSocketSessionType sessionType) {
        this.sessionId = sessionId;
        this.securityUser = securityUser;
        this.localAddress = localAddress;
        this.remoteAddress = remoteAddress;
        this.sessionType = sessionType;
    }

    public String getSessionId() {
        return sessionId;
    }

    public SecurityUser getSecurityPrincipal() {
        return securityUser;
    }

    public void setSecurityPrincipal(SecurityUser securityUser) {
        this.securityUser = securityUser;
    }

    public InetSocketAddress getLocalAddress() {
        return localAddress;
    }

    public InetSocketAddress getRemoteAddress() {
        return remoteAddress;
    }

    public WebSocketSessionType getSessionType() {
        return sessionType;
    }
}
