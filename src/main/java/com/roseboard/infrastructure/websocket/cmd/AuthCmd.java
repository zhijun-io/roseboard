package com.roseboard.infrastructure.websocket.cmd;

public class AuthCmd implements WsCmd {
    private int cmdId;
    private String token;
    private String apiKey;

    public AuthCmd() {
    }

    public AuthCmd(int cmdId, String token, String apiKey) {
        this.cmdId = cmdId;
        this.token = token;
        this.apiKey = apiKey;
    }

    @Override
    public int getCmdId() {
        return cmdId;
    }

    public void setCmdId(int cmdId) {
        this.cmdId = cmdId;
    }

    public String getToken() {
        return token;
    }

    public void setToken(String token) {
        this.token = token;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    @Override
    public WsCmdType getType() {
        return WsCmdType.AUTH;
    }
}
