package com.roseboard.infrastructure.security.oauth2.model;

import java.util.List;
import java.util.UUID;

public class OAuth2ClientRegistrationTemplate {
    private UUID id;
    private Long createdTime;
    private String providerId;
    private OAuth2MapperConfig mapperConfig;
    private String authorizationUri;
    private String accessTokenUri;
    private List<String> scope;
    private String userInfoUri;
    private String userNameAttributeName;
    private String jwkSetUri;
    private String clientAuthenticationMethod;
    private String comment;
    private String loginButtonIcon;
    private String loginButtonLabel;
    private String helpLink;
    public UUID getId(){return id;} public void setId(UUID v){id=v;}
    public Long getCreatedTime(){return createdTime;} public void setCreatedTime(Long v){createdTime=v;}
    public String getProviderId(){return providerId;} public void setProviderId(String v){providerId=v;}
    public OAuth2MapperConfig getMapperConfig(){return mapperConfig;} public void setMapperConfig(OAuth2MapperConfig v){mapperConfig=v;}
    public String getAuthorizationUri(){return authorizationUri;} public void setAuthorizationUri(String v){authorizationUri=v;}
    public String getAccessTokenUri(){return accessTokenUri;} public void setAccessTokenUri(String v){accessTokenUri=v;}
    public List<String> getScope(){return scope;} public void setScope(List<String> v){scope=v;}
    public String getUserInfoUri(){return userInfoUri;} public void setUserInfoUri(String v){userInfoUri=v;}
    public String getUserNameAttributeName(){return userNameAttributeName;} public void setUserNameAttributeName(String v){userNameAttributeName=v;}
    public String getJwkSetUri(){return jwkSetUri;} public void setJwkSetUri(String v){jwkSetUri=v;}
    public String getClientAuthenticationMethod(){return clientAuthenticationMethod;} public void setClientAuthenticationMethod(String v){clientAuthenticationMethod=v;}
    public String getComment(){return comment;} public void setComment(String v){comment=v;}
    public String getLoginButtonIcon(){return loginButtonIcon;} public void setLoginButtonIcon(String v){loginButtonIcon=v;}
    public String getLoginButtonLabel(){return loginButtonLabel;} public void setLoginButtonLabel(String v){loginButtonLabel=v;}
    public String getHelpLink(){return helpLink;} public void setHelpLink(String v){helpLink=v;}
}
