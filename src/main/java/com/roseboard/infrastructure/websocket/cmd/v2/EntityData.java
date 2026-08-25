package com.roseboard.infrastructure.websocket.cmd.v2;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class EntityData {
    private EntityIdRef entityId;
    private Map<EntityKeyType, Map<String, TsValue>> latest;

    public EntityData() {
    }

    public EntityData(EntityIdRef entityId, Map<EntityKeyType, Map<String, TsValue>> latest) {
        this.entityId = entityId;
        this.latest = latest;
    }

    public EntityIdRef getEntityId() {
        return entityId;
    }

    public void setEntityId(EntityIdRef entityId) {
        this.entityId = entityId;
    }

    public Map<EntityKeyType, Map<String, TsValue>> getLatest() {
        return latest;
    }

    public void setLatest(Map<EntityKeyType, Map<String, TsValue>> latest) {
        this.latest = latest;
    }
}
