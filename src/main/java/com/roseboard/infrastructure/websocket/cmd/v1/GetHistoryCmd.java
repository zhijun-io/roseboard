package com.roseboard.infrastructure.websocket.cmd.v1;

import com.roseboard.infrastructure.websocket.cmd.WsCmdType;
import com.roseboard.infrastructure.websocket.cmd.WsTelemetryCmd;

public class GetHistoryCmd implements WsTelemetryCmd {
    private int cmdId;
    private String entityType;
    private String entityId;
    private String keys;
    private long startTs;
    private long endTs;
    private long interval;
    private int limit;
    private String agg;

    public GetHistoryCmd() {
    }

    @Override
    public int getCmdId() {
        return cmdId;
    }

    public void setCmdId(int cmdId) {
        this.cmdId = cmdId;
    }

    public String getEntityType() {
        return entityType;
    }

    public void setEntityType(String entityType) {
        this.entityType = entityType;
    }

    public String getEntityId() {
        return entityId;
    }

    public void setEntityId(String entityId) {
        this.entityId = entityId;
    }

    public String getKeys() {
        return keys;
    }

    public void setKeys(String keys) {
        this.keys = keys;
    }

    public long getStartTs() {
        return startTs;
    }

    public void setStartTs(long startTs) {
        this.startTs = startTs;
    }

    public long getEndTs() {
        return endTs;
    }

    public void setEndTs(long endTs) {
        this.endTs = endTs;
    }

    public long getInterval() {
        return interval;
    }

    public void setInterval(long interval) {
        this.interval = interval;
    }

    public int getLimit() {
        return limit;
    }

    public void setLimit(int limit) {
        this.limit = limit;
    }

    public String getAgg() {
        return agg;
    }

    public void setAgg(String agg) {
        this.agg = agg;
    }

    @Override
    public WsCmdType getType() {
        return WsCmdType.TIMESERIES_HISTORY;
    }
}
