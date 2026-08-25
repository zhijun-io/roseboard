package com.roseboard.infrastructure.websocket.cmd.v2;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.roseboard.infrastructure.websocket.cmd.WsCmd;
import com.roseboard.infrastructure.websocket.cmd.WsCmdType;

@JsonIgnoreProperties(ignoreUnknown = true)
public class EntityCountCmd implements WsCmd {
    private int cmdId;
    private EntityFilterQuery query;

    public EntityCountCmd() {
    }

    @Override
    public int getCmdId() {
        return cmdId;
    }

    public void setCmdId(int cmdId) {
        this.cmdId = cmdId;
    }

    public EntityFilterQuery getQuery() {
        return query;
    }

    public void setQuery(EntityFilterQuery query) {
        this.query = query;
    }

    @Override
    public WsCmdType getType() {
        return WsCmdType.ENTITY_COUNT;
    }
}
