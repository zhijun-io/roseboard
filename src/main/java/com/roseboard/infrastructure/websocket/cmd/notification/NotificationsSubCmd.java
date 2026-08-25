package com.roseboard.infrastructure.websocket.cmd.notification;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.roseboard.infrastructure.websocket.cmd.WsCmd;
import com.roseboard.infrastructure.websocket.cmd.WsCmdType;

import java.util.Set;

public class NotificationsSubCmd implements WsCmd {
    private int cmdId;
    private int limit;
    private Set<String> types;

    @Override
    public int getCmdId() {
        return cmdId;
    }

    public void setCmdId(int cmdId) {
        this.cmdId = cmdId;
    }

    public int getLimit() {
        return limit;
    }

    public void setLimit(int limit) {
        this.limit = limit;
    }

    public Set<String> getTypes() {
        return types;
    }

    public void setTypes(Set<String> types) {
        this.types = types;
    }

    @JsonIgnore
    @Override
    public WsCmdType getType() {
        return WsCmdType.NOTIFICATIONS;
    }
}
