package com.roseboard.infrastructure.websocket.cmd.v1;

import com.roseboard.infrastructure.websocket.cmd.WsCmdType;

public class TimeseriesSubscriptionCmd extends SubscriptionCmd {
    private long startTs;
    private long timeWindow;
    private long interval;
    private int limit;
    private String agg;

    public TimeseriesSubscriptionCmd() {
    }

    public long getStartTs() {
        return startTs;
    }

    public void setStartTs(long startTs) {
        this.startTs = startTs;
    }

    public long getTimeWindow() {
        return timeWindow;
    }

    public void setTimeWindow(long timeWindow) {
        this.timeWindow = timeWindow;
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
        return WsCmdType.TIMESERIES;
    }
}
