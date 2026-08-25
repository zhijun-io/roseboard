package com.roseboard.infrastructure.websocket.cmd.notification;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.roseboard.infrastructure.websocket.cmd.v2.CmdUpdate;
import com.roseboard.infrastructure.websocket.cmd.v2.CmdUpdateType;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class NotificationsCountUpdate extends CmdUpdate {
    private int totalUnreadCount;
    private int sequenceNumber;

    public NotificationsCountUpdate() {
    }

    public NotificationsCountUpdate(int cmdId, int totalUnreadCount, int sequenceNumber) {
        super(cmdId, 0, null);
        this.totalUnreadCount = totalUnreadCount;
        this.sequenceNumber = sequenceNumber;
    }

    public NotificationsCountUpdate(int cmdId, int errorCode, String errorMsg) {
        super(cmdId, errorCode, errorMsg);
    }

    @Override
    public CmdUpdateType getCmdUpdateType() {
        return CmdUpdateType.NOTIFICATIONS_COUNT;
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
