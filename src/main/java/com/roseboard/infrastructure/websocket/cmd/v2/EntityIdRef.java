package com.roseboard.infrastructure.websocket.cmd.v2;

public class EntityIdRef {
    private String entityType;
    private String id;

    public EntityIdRef() {
    }

    public EntityIdRef(String entityType, String id) {
        this.entityType = entityType;
        this.id = id;
    }

    public String getEntityType() {
        return entityType;
    }

    public void setEntityType(String entityType) {
        this.entityType = entityType;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }
}
