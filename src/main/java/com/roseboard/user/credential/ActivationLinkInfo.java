package com.roseboard.user.credential;

public class ActivationLinkInfo {
    private String activateToken;
    private Long activateTokenExpTime;

    public String getActivateToken() { return activateToken; }
    public void setActivateToken(String activateToken) { this.activateToken = activateToken; }
    public Long getActivateTokenExpTime() { return activateTokenExpTime; }
    public void setActivateTokenExpTime(Long activateTokenExpTime) { this.activateTokenExpTime = activateTokenExpTime; }
}
