package com.roseboard.infrastructure.websocket.cmd;

import com.fasterxml.jackson.annotation.JsonIgnore;

public interface WsCmd {
    int getCmdId();

    @JsonIgnore
    WsCmdType getType();
}
