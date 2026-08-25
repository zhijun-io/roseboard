package com.roseboard.infrastructure.websocket.cmd.v1;

import com.roseboard.infrastructure.websocket.cmd.WsCmdType;

public class AttributesSubscriptionCmd extends SubscriptionCmd {

    public AttributesSubscriptionCmd() {
    }

    @Override
    public WsCmdType getType() {
        return WsCmdType.ATTRIBUTES;
    }
}
