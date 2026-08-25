package com.roseboard.infrastructure.websocket.cmd.v2;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public abstract class CmdUpdate {
    private int cmdId;
    private int errorCode;
    private String errorMsg;

    protected CmdUpdate() {
    }

    protected CmdUpdate(int cmdId, int errorCode, String errorMsg) {
        this.cmdId = cmdId;
        this.errorCode = errorCode;
        this.errorMsg = errorMsg;
    }

    public abstract CmdUpdateType getCmdUpdateType();

    public int getCmdId() {
        return cmdId;
    }

    public void setCmdId(int cmdId) {
        this.cmdId = cmdId;
    }

    public int getErrorCode() {
        return errorCode;
    }

    public void setErrorCode(int errorCode) {
        this.errorCode = errorCode;
    }

    public String getErrorMsg() {
        return errorMsg;
    }

    public void setErrorMsg(String errorMsg) {
        this.errorMsg = errorMsg;
    }
}
