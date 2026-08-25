package com.roseboard.infrastructure.websocket.cmd.plugin;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.roseboard.infrastructure.websocket.cmd.WsCommandsWrapper;
import com.roseboard.infrastructure.websocket.cmd.notification.MarkAllNotificationsAsReadCmd;
import com.roseboard.infrastructure.websocket.cmd.notification.MarkNotificationsAsReadCmd;
import com.roseboard.infrastructure.websocket.cmd.notification.NotificationsCountSubCmd;
import com.roseboard.infrastructure.websocket.cmd.notification.NotificationsSubCmd;
import com.roseboard.infrastructure.websocket.cmd.notification.NotificationsUnsubCmd;

import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * TB legacy notifications plugin envelope ({@code /api/ws/plugins/notifications}).
 */
public class NotificationCmdsWrapper {
    private NotificationsCountSubCmd unreadCountSubCmd;
    private NotificationsSubCmd unreadSubCmd;
    private MarkNotificationsAsReadCmd markAsReadCmd;
    private MarkAllNotificationsAsReadCmd markAllAsReadCmd;
    private NotificationsUnsubCmd unsubCmd;

    public NotificationsCountSubCmd getUnreadCountSubCmd() {
        return unreadCountSubCmd;
    }

    public void setUnreadCountSubCmd(NotificationsCountSubCmd unreadCountSubCmd) {
        this.unreadCountSubCmd = unreadCountSubCmd;
    }

    public NotificationsSubCmd getUnreadSubCmd() {
        return unreadSubCmd;
    }

    public void setUnreadSubCmd(NotificationsSubCmd unreadSubCmd) {
        this.unreadSubCmd = unreadSubCmd;
    }

    public MarkNotificationsAsReadCmd getMarkAsReadCmd() {
        return markAsReadCmd;
    }

    public void setMarkAsReadCmd(MarkNotificationsAsReadCmd markAsReadCmd) {
        this.markAsReadCmd = markAsReadCmd;
    }

    public MarkAllNotificationsAsReadCmd getMarkAllAsReadCmd() {
        return markAllAsReadCmd;
    }

    public void setMarkAllAsReadCmd(MarkAllNotificationsAsReadCmd markAllAsReadCmd) {
        this.markAllAsReadCmd = markAllAsReadCmd;
    }

    public NotificationsUnsubCmd getUnsubCmd() {
        return unsubCmd;
    }

    public void setUnsubCmd(NotificationsUnsubCmd unsubCmd) {
        this.unsubCmd = unsubCmd;
    }

    @JsonIgnore
    public WsCommandsWrapper toCommonCmdsWrapper() {
        return new WsCommandsWrapper(null, Stream.of(
                        unreadCountSubCmd, unreadSubCmd, markAsReadCmd, markAllAsReadCmd, unsubCmd)
                .filter(Objects::nonNull)
                .collect(Collectors.toList()));
    }
}
