package com.roseboard.infrastructure.websocket.cmd.v2;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.roseboard.infrastructure.websocket.cmd.WsCmd;
import com.roseboard.infrastructure.websocket.cmd.WsCmdType;

@JsonIgnoreProperties(ignoreUnknown = true)
public class EntityDataCmd implements WsCmd {
    private int cmdId;
    private EntityFilterQuery query;
    private LatestValueCmd latestCmd;

    public EntityDataCmd() {
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

    public LatestValueCmd getLatestCmd() {
        return latestCmd;
    }

    public void setLatestCmd(LatestValueCmd latestCmd) {
        this.latestCmd = latestCmd;
    }

    @Override
    public WsCmdType getType() {
        return WsCmdType.ENTITY_DATA;
    }
}
