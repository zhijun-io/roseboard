package com.roseboard.infrastructure.websocket.cmd.v2;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.roseboard.infrastructure.websocket.SubscriptionErrorCode;

import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class EntityDataUpdate extends DataUpdate<EntityData> {
    public EntityDataUpdate() {
    }

    public EntityDataUpdate(int cmdId, WsPageData<EntityData> data) {
        super(cmdId, data, null, 0, null);
    }

    public EntityDataUpdate(int cmdId, SubscriptionErrorCode errorCode, String errorMsg) {
        super(cmdId, null, null, errorCode.getCode(), errorMsg != null ? errorMsg : errorCode.getDefaultMsg());
    }

    public EntityDataUpdate(int cmdId, WsPageData<EntityData> data, List<EntityData> update) {
        super(cmdId, data, update, 0, null);
    }

    @Override
    public CmdUpdateType getCmdUpdateType() {
        return CmdUpdateType.ENTITY_DATA;
    }
}
