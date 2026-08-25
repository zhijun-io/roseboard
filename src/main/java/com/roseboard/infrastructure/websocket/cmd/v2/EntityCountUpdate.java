package com.roseboard.infrastructure.websocket.cmd.v2;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.roseboard.infrastructure.websocket.SubscriptionErrorCode;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class EntityCountUpdate extends CmdUpdate {
    private int count;

    public EntityCountUpdate() {
    }

    public EntityCountUpdate(int cmdId, int count) {
        super(cmdId, 0, null);
        this.count = count;
    }

    public EntityCountUpdate(int cmdId, SubscriptionErrorCode errorCode, String errorMsg) {
        super(cmdId, errorCode.getCode(), errorMsg != null ? errorMsg : errorCode.getDefaultMsg());
    }

    @Override
    public CmdUpdateType getCmdUpdateType() {
        return CmdUpdateType.COUNT_DATA;
    }

    public int getCount() {
        return count;
    }

    public void setCount(int count) {
        this.count = count;
    }
}
