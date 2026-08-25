package com.roseboard.infrastructure.websocket.cmd.v2;

import java.util.List;

public class LatestValueCmd {
    private List<EntityKey> keys;

    public LatestValueCmd() {
    }

    public List<EntityKey> getKeys() {
        return keys;
    }

    public void setKeys(List<EntityKey> keys) {
        this.keys = keys;
    }
}
