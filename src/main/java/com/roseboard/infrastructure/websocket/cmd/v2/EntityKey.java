package com.roseboard.infrastructure.websocket.cmd.v2;

public class EntityKey {
    private EntityKeyType type;
    private String key;

    public EntityKey() {
    }

    public EntityKey(EntityKeyType type, String key) {
        this.type = type;
        this.key = key;
    }

    public EntityKeyType getType() {
        return type;
    }

    public void setType(EntityKeyType type) {
        this.type = type;
    }

    public String getKey() {
        return key;
    }

    public void setKey(String key) {
        this.key = key;
    }
}
