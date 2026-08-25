package com.roseboard.infrastructure.websocket.cmd.v2;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public abstract class DataUpdate<T> extends CmdUpdate {
    private WsPageData<T> data;
    private List<T> update;

    protected DataUpdate() {
    }

    protected DataUpdate(int cmdId, WsPageData<T> data, List<T> update, int errorCode, String errorMsg) {
        super(cmdId, errorCode, errorMsg);
        this.data = data;
        this.update = update;
    }

    public WsPageData<T> getData() {
        return data;
    }

    public void setData(WsPageData<T> data) {
        this.data = data;
    }

    public List<T> getUpdate() {
        return update;
    }

    public void setUpdate(List<T> update) {
        this.update = update;
    }
}
