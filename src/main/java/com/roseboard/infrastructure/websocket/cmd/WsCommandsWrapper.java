package com.roseboard.infrastructure.websocket.cmd;

import com.roseboard.infrastructure.websocket.cmd.v1.AttributesSubscriptionCmd;
import com.roseboard.infrastructure.websocket.cmd.v1.GetHistoryCmd;
import com.roseboard.infrastructure.websocket.cmd.v1.TimeseriesSubscriptionCmd;
import com.roseboard.infrastructure.websocket.cmd.v2.EntityDataCmd;
import com.roseboard.infrastructure.websocket.cmd.v2.EntityDataUnsubscribeCmd;
import com.roseboard.infrastructure.websocket.cmd.v2.EntityCountCmd;
import com.roseboard.infrastructure.websocket.cmd.v2.EntityCountUnsubscribeCmd;
import com.roseboard.infrastructure.websocket.cmd.notification.MarkAllNotificationsAsReadCmd;
import com.roseboard.infrastructure.websocket.cmd.notification.MarkNotificationsAsReadCmd;
import com.roseboard.infrastructure.websocket.cmd.notification.NotificationsCountSubCmd;
import com.roseboard.infrastructure.websocket.cmd.notification.NotificationsSubCmd;
import com.roseboard.infrastructure.websocket.cmd.notification.NotificationsUnsubCmd;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import java.util.List;

public class WsCommandsWrapper {
    private AuthCmd authCmd;

    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
    @JsonSubTypes({
            @JsonSubTypes.Type(value = AttributesSubscriptionCmd.class, name = "ATTRIBUTES"),
            @JsonSubTypes.Type(value = TimeseriesSubscriptionCmd.class, name = "TIMESERIES"),
            @JsonSubTypes.Type(value = GetHistoryCmd.class, name = "TIMESERIES_HISTORY"),
            @JsonSubTypes.Type(value = EntityDataCmd.class, name = "ENTITY_DATA"),
            @JsonSubTypes.Type(value = EntityDataUnsubscribeCmd.class, name = "ENTITY_DATA_UNSUBSCRIBE"),
            @JsonSubTypes.Type(value = EntityCountCmd.class, name = "ENTITY_COUNT"),
            @JsonSubTypes.Type(value = EntityCountUnsubscribeCmd.class, name = "ENTITY_COUNT_UNSUBSCRIBE"),
            @JsonSubTypes.Type(value = NotificationsSubCmd.class, name = "NOTIFICATIONS"),
            @JsonSubTypes.Type(value = NotificationsCountSubCmd.class, name = "NOTIFICATIONS_COUNT"),
            @JsonSubTypes.Type(value = MarkNotificationsAsReadCmd.class, name = "MARK_NOTIFICATIONS_AS_READ"),
            @JsonSubTypes.Type(value = MarkAllNotificationsAsReadCmd.class, name = "MARK_ALL_NOTIFICATIONS_AS_READ"),
            @JsonSubTypes.Type(value = NotificationsUnsubCmd.class, name = "NOTIFICATIONS_UNSUBSCRIBE")
    })
    private List<WsCmd> cmds;

    public WsCommandsWrapper() {
    }

    public WsCommandsWrapper(AuthCmd authCmd, List<WsCmd> cmds) {
        this.authCmd = authCmd;
        this.cmds = cmds;
    }

    public AuthCmd getAuthCmd() {
        return authCmd;
    }

    public void setAuthCmd(AuthCmd authCmd) {
        this.authCmd = authCmd;
    }

    public List<WsCmd> getCmds() {
        return cmds;
    }

    public void setCmds(List<WsCmd> cmds) {
        this.cmds = cmds;
    }
}
