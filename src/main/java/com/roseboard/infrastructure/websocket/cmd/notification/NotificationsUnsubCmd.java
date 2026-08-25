package com.roseboard.infrastructure.websocket.cmd.notification;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.roseboard.infrastructure.websocket.cmd.WsCmd;
import com.roseboard.infrastructure.websocket.cmd.WsCmdType;

public class NotificationsUnsubCmd implements WsCmd {
    private int cmdId;

    @Override
    public int getCmdId() {
        return cmdId;
    }

    public void setCmdId(int cmdId) {
        this.cmdId = cmdId;
    }

    @JsonIgnore
    @Override
    public WsCmdType getType() {
        return WsCmdType.NOTIFICATIONS_UNSUBSCRIBE;
    }
}
