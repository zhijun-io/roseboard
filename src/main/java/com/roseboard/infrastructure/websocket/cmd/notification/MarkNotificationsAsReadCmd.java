package com.roseboard.infrastructure.websocket.cmd.notification;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.roseboard.infrastructure.websocket.cmd.WsCmd;
import com.roseboard.infrastructure.websocket.cmd.WsCmdType;

import java.util.List;
import java.util.UUID;

public class MarkNotificationsAsReadCmd implements WsCmd {
    private int cmdId;
    private List<UUID> notifications;

    @Override
    public int getCmdId() {
        return cmdId;
    }

    public void setCmdId(int cmdId) {
        this.cmdId = cmdId;
    }

    public List<UUID> getNotifications() {
        return notifications;
    }

    public void setNotifications(List<UUID> notifications) {
        this.notifications = notifications;
    }

    @JsonIgnore
    @Override
    public WsCmdType getType() {
        return WsCmdType.MARK_NOTIFICATIONS_AS_READ;
    }
}
