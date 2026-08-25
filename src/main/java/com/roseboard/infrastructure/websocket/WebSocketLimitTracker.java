package com.roseboard.infrastructure.websocket;

import com.roseboard.infrastructure.security.api.SecurityUser;
import com.roseboard.infrastructure.websocket.cmd.v1.SubscriptionCmd;
import com.roseboard.infrastructure.websocket.limit.WebSocketLimitStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Component
public class WebSocketLimitTracker {
    private static final Logger log = LoggerFactory.getLogger(WebSocketLimitTracker.class);

    private final TenantProfileWsLimits wsLimits;
    private final WebSocketLimitStore limitStore;
    private final WebSocketMsgEndpoint msgEndpoint;

    public WebSocketLimitTracker(TenantProfileWsLimits wsLimits,
                                 WebSocketLimitStore limitStore,
                                 @Lazy WebSocketMsgEndpoint msgEndpoint) {
        this.wsLimits = wsLimits;
        this.limitStore = limitStore;
        this.msgEndpoint = msgEndpoint;
    }

    public boolean tryAcquireSession(WebSocketSession session, WebSocketSessionRef sessionRef) throws IOException {
        String reason = acquire(sessionRef.getSecurityPrincipal(), sessionRef.getSessionId(), true);
        if (reason == null) {
            return true;
        }
        log.info("{} {}", sessionRef.getSessionId(), reason);
        session.close(CloseStatus.POLICY_VIOLATION.withReason(reason));
        return false;
    }

    public void releaseSession(WebSocketSessionRef sessionRef) {
        release(sessionRef.getSecurityPrincipal(), sessionRef.getSessionId(), true);
    }

    public boolean processSubscription(WebSocketSessionRef sessionRef, SubscriptionCmd cmd) throws IOException {
        SecurityUser user = sessionRef.getSecurityPrincipal();
        if (user == null) {
            return true;
        }
        String member = WebSocketLimitStore.subscriptionId(sessionRef.getSessionId(), cmd.getCmdId());
        if (cmd.isUnsubscribe()) {
            release(user, member, false);
            return true;
        }
        String reason = acquire(user, member, false);
        if (reason == null) {
            return true;
        }
        log.info("[{}] {}", sessionRef.getSessionId(), reason);
        msgEndpoint.close(sessionRef, CloseStatus.POLICY_VIOLATION.withReason(reason));
        return false;
    }

    public void releaseSessionSubscriptions(WebSocketSessionRef sessionRef) {
        SecurityUser user = sessionRef.getSecurityPrincipal();
        if (user == null) {
            return;
        }
        removeByPrefix(user, WebSocketLimitStore.sessionPrefix(sessionRef.getSessionId()), false);
    }

    private String acquire(SecurityUser user, String member, boolean sessionScope) {
        if (user == null) {
            return null;
        }
        UUID tenantId = user.getTenantId();
        List<BucketRef> acquired = new ArrayList<>();

        long tenantLimit = sessionScope
                ? wsLimits.maxWsSessionsPerTenant(tenantId)
                : wsLimits.maxWsSubscriptionsPerTenant(tenantId);
        if (!tryAdd(acquired, sessionScope ? WebSocketLimitStore.tenantSessions(tenantId)
                : WebSocketLimitStore.tenantSubscriptions(tenantId), member, tenantLimit)) {
            rollback(acquired);
            return sessionScope ? "Max tenant sessions limit reached" : "Max tenant subscriptions limit reached!";
        }

        UUID customerId = user.getCustomerId();
        if (customerId != null) {
            long customerLimit = sessionScope
                    ? wsLimits.maxWsSessionsPerCustomer(tenantId)
                    : wsLimits.maxWsSubscriptionsPerCustomer(tenantId);
            if (!tryAdd(acquired, sessionScope ? WebSocketLimitStore.customerSessions(customerId)
                    : WebSocketLimitStore.customerSubscriptions(customerId), member, customerLimit)) {
                rollback(acquired);
                return sessionScope ? "Max customer sessions limit reached" : "Max customer subscriptions limit reached";
            }

            if (user.isPublicUser()) {
                long publicLimit = sessionScope
                        ? wsLimits.maxWsSessionsPerPublicUser(tenantId)
                        : wsLimits.maxWsSubscriptionsPerPublicUser(tenantId);
                if (!tryAdd(acquired, sessionScope ? WebSocketLimitStore.publicUserSessions(tenantId)
                        : WebSocketLimitStore.publicUserSubscriptions(tenantId), member, publicLimit)) {
                    rollback(acquired);
                    return sessionScope ? "Max public user sessions limit reached" : "Max public user subscriptions limit reached";
                }
            } else {
                long userLimit = sessionScope
                        ? wsLimits.maxWsSessionsPerRegularUser(tenantId)
                        : wsLimits.maxWsSubscriptionsPerRegularUser(tenantId);
                if (!tryAdd(acquired, sessionScope ? WebSocketLimitStore.regularUserSessions(user.getUserId())
                        : WebSocketLimitStore.regularUserSubscriptions(user.getUserId()), member, userLimit)) {
                    rollback(acquired);
                    return sessionScope ? "Max regular user sessions limit reached" : "Max regular user subscriptions limit reached";
                }
            }
        }
        return null;
    }

    private void release(SecurityUser user, String member, boolean sessionScope) {
        if (user == null) {
            return;
        }
        UUID tenantId = user.getTenantId();
        limitStore.remove(sessionScope ? WebSocketLimitStore.tenantSessions(tenantId)
                : WebSocketLimitStore.tenantSubscriptions(tenantId), member);

        UUID customerId = user.getCustomerId();
        if (customerId == null) {
            return;
        }
        limitStore.remove(sessionScope ? WebSocketLimitStore.customerSessions(customerId)
                : WebSocketLimitStore.customerSubscriptions(customerId), member);
        if (user.isPublicUser()) {
            limitStore.remove(sessionScope ? WebSocketLimitStore.publicUserSessions(tenantId)
                    : WebSocketLimitStore.publicUserSubscriptions(tenantId), member);
        } else {
            limitStore.remove(sessionScope ? WebSocketLimitStore.regularUserSessions(user.getUserId())
                    : WebSocketLimitStore.regularUserSubscriptions(user.getUserId()), member);
        }
    }

    private void removeByPrefix(SecurityUser user, String prefix, boolean sessionScope) {
        if (user == null) {
            return;
        }
        UUID tenantId = user.getTenantId();
        limitStore.removeByPrefix(sessionScope ? WebSocketLimitStore.tenantSessions(tenantId)
                : WebSocketLimitStore.tenantSubscriptions(tenantId), prefix);

        UUID customerId = user.getCustomerId();
        if (customerId == null) {
            return;
        }
        limitStore.removeByPrefix(sessionScope ? WebSocketLimitStore.customerSessions(customerId)
                : WebSocketLimitStore.customerSubscriptions(customerId), prefix);
        if (user.isPublicUser()) {
            limitStore.removeByPrefix(sessionScope ? WebSocketLimitStore.publicUserSessions(tenantId)
                    : WebSocketLimitStore.publicUserSubscriptions(tenantId), prefix);
        } else {
            limitStore.removeByPrefix(sessionScope ? WebSocketLimitStore.regularUserSessions(user.getUserId())
                    : WebSocketLimitStore.regularUserSubscriptions(user.getUserId()), prefix);
        }
    }

    private boolean tryAdd(List<BucketRef> acquired, String bucketKey, String member, long limit) {
        if (limit <= 0) {
            return true;
        }
        if (limitStore.tryAdd(bucketKey, member, limit)) {
            acquired.add(new BucketRef(bucketKey, member));
            return true;
        }
        return false;
    }

    private void rollback(List<BucketRef> acquired) {
        for (BucketRef ref : acquired) {
            limitStore.remove(ref.bucketKey(), ref.member());
        }
    }

    private record BucketRef(String bucketKey, String member) {
    }
}
