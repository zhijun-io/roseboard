package com.roseboard.infrastructure.websocket.cmd.v1;

import com.roseboard.infrastructure.websocket.cmd.WsTelemetryCmd;

public abstract class SubscriptionCmd implements WsTelemetryCmd {
    private int cmdId;
    private String entityType;
    private String entityId;
    private String keys;
    private String scope;
    private boolean unsubscribe;

    protected SubscriptionCmd() {
    }

    protected SubscriptionCmd(int cmdId, String entityType, String entityId,
                              String keys, String scope, boolean unsubscribe) {
        this.cmdId = cmdId;
        this.entityType = entityType;
        this.entityId = entityId;
        this.keys = keys;
        this.scope = scope;
        this.unsubscribe = unsubscribe;
    }

    @Override
    public int getCmdId() {
        return cmdId;
    }

    public void setCmdId(int cmdId) {
        this.cmdId = cmdId;
    }

    public String getEntityType() {
        return entityType;
    }

    public void setEntityType(String entityType) {
        this.entityType = entityType;
    }

    public String getEntityId() {
        return entityId;
    }

    public void setEntityId(String entityId) {
        this.entityId = entityId;
    }

    public String getKeys() {
        return keys;
    }

    public void setKeys(String keys) {
        this.keys = keys;
    }

    public String getScope() {
        return scope;
    }

    public void setScope(String scope) {
        this.scope = scope;
    }

    public boolean isUnsubscribe() {
        return unsubscribe;
    }

    public void setUnsubscribe(boolean unsubscribe) {
        this.unsubscribe = unsubscribe;
    }
}
