package com.roseboard.infrastructure.websocket.cmd.v2;

import com.roseboard.infrastructure.websocket.cmd.WsCmd;

abstract class AbstractV2UnsubscribeCmd implements WsCmd {
    private int cmdId;

    @Override
    public int getCmdId() {
        return cmdId;
    }

    public void setCmdId(int cmdId) {
        this.cmdId = cmdId;
    }
}
