package com.roseboard.infrastructure.websocket.cmd.v2;

import com.roseboard.infrastructure.websocket.cmd.WsCmdType;

public class EntityCountUnsubscribeCmd extends AbstractV2UnsubscribeCmd {
    @Override
    public WsCmdType getType() {
        return WsCmdType.ENTITY_COUNT_UNSUBSCRIBE;
    }
}
