package com.roseboard.infrastructure.security.oauth2.model;

public class OAuth2CustomMapperConfig {
    private String url;
    private String username;
    private String password;
    private boolean sendToken;
    public String getUrl(){return url;} public void setUrl(String v){url=v;}
    public String getUsername(){return username;} public void setUsername(String v){username=v;}
    public String getPassword(){return password;} public void setPassword(String v){password=v;}
    public boolean isSendToken(){return sendToken;} public void setSendToken(boolean v){sendToken=v;}
}
