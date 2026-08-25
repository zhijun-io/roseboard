package com.roseboard.device.query.filter;

public class SingleEntityFilter {
    private String entityType;
    private String entityId;

    public SingleEntityFilter() {
    }

    public SingleEntityFilter(String entityType, String entityId) {
        this.entityType = entityType;
        this.entityId = entityId;
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

    public EntityFilterType getType() {
        return EntityFilterType.SINGLE_ENTITY;
    }
}
