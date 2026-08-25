package com.roseboard.infrastructure.websocket.cmd.v2;

import com.roseboard.infrastructure.websocket.cmd.WsCmdType;

public class EntityDataUnsubscribeCmd extends AbstractV2UnsubscribeCmd {
    @Override
    public WsCmdType getType() {
        return WsCmdType.ENTITY_DATA_UNSUBSCRIBE;
    }
}
