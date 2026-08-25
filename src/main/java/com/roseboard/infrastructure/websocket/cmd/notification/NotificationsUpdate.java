package com.roseboard.infrastructure.websocket.cmd.notification;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.roseboard.infrastructure.websocket.cmd.v2.CmdUpdate;
import com.roseboard.infrastructure.websocket.cmd.v2.CmdUpdateType;
import com.roseboard.notification.NotificationEntity;

import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class NotificationsUpdate extends CmdUpdate {
    private List<NotificationEntity> notifications;
    private NotificationEntity update;
    private int totalUnreadCount;
    private int sequenceNumber;

    public NotificationsUpdate() {
    }

    public NotificationsUpdate(int cmdId, List<NotificationEntity> notifications,
                               NotificationEntity update, int totalUnreadCount, int sequenceNumber) {
        super(cmdId, 0, null);
        this.notifications = notifications;
        this.update = update;
        this.totalUnreadCount = totalUnreadCount;
        this.sequenceNumber = sequenceNumber;
    }

    public NotificationsUpdate(int cmdId, int errorCode, String errorMsg) {
        super(cmdId, errorCode, errorMsg);
    }

    @Override
    public CmdUpdateType getCmdUpdateType() {
        return CmdUpdateType.NOTIFICATIONS;
    }

    public List<NotificationEntity> getNotifications() {
        return notifications;
    }

    public void setNotifications(List<NotificationEntity> notifications) {
        this.notifications = notifications;
    }

    public NotificationEntity getUpdate() {
        return update;
    }

    public void setUpdate(NotificationEntity update) {
        this.update = update;
    }

    public int getTotalUnreadCount() {
        return totalUnreadCount;
    }

    public void setTotalUnreadCount(int totalUnreadCount) {
        this.totalUnreadCount = totalUnreadCount;
    }

    public int getSequenceNumber() {
        return sequenceNumber;
    }

    public void setSequenceNumber(int sequenceNumber) {
        this.sequenceNumber = sequenceNumber;
    }
}
