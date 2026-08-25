package com.roseboard.setting.oauth2.client;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.Jackson3TypeHandler;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import tools.jackson.databind.JsonNode;

import java.util.UUID;

@TableName(value = "oauth2_client", autoResultMap = true)
public class OAuth2ClientEntity {
    @TableId(type = IdType.ASSIGN_UUID) private UUID id;
    private Long createdTime;
    private UUID tenantId;
    private String title;
    @TableField(typeHandler = Jackson3TypeHandler.class) private JsonNode additionalInfo;
    private String clientId;
    private String clientSecret;
    private String authorizationUri;
    private String tokenUri;
    private String scope;
    private String redirectUri;
    private String userInfoUri;
    private String userNameAttributeName;
    private String jwkSetUri;
    private String clientAuthenticationMethod;
    private String loginButtonLabel;
    private String loginButtonIcon;
    private String platforms;
    private Boolean allowUserCreation;
    private Boolean activateUser;
    private String type;
    private String basicEmailAttributeKey;
    private String basicFirstNameAttributeKey;
    private String basicLastNameAttributeKey;
    private String basicTenantNameStrategy;
    private String basicTenantNamePattern;
    private String basicCustomerNamePattern;
    private String basicDefaultDashboardName;
    private Boolean basicAlwaysFullScreen;
    private String customUrl;
    private String customUsername;
    private String customPassword;
    private Boolean customSendToken;

    public UUID getId(){return id;} public void setId(UUID v){id=v;}
    public Long getCreatedTime(){return createdTime;} public void setCreatedTime(Long v){createdTime=v;}
    public UUID getTenantId(){return tenantId;} public void setTenantId(UUID v){tenantId=v;}
    public String getTitle(){return title;} public void setTitle(String v){title=v;}
    public JsonNode getAdditionalInfo(){return additionalInfo;} public void setAdditionalInfo(JsonNode v){additionalInfo=v;}
    public String getClientId(){return clientId;} public void setClientId(String v){clientId=v;}
    public String getClientSecret(){return clientSecret;} public void setClientSecret(String v){clientSecret=v;}
    public String getAuthorizationUri(){return authorizationUri;} public void setAuthorizationUri(String v){authorizationUri=v;}
    @JsonProperty("accessTokenUri") public String getAccessTokenUri(){return tokenUri;}
    @JsonProperty("accessTokenUri") public void setAccessTokenUri(String v){tokenUri=v;}
    @JsonIgnore public String getTokenUri(){return tokenUri;}
    public String getScope(){return scope;} public void setScope(String v){scope=v;}
    public String getRedirectUri(){return redirectUri;} public void setRedirectUri(String v){redirectUri=v;}
    public String getUserInfoUri(){return userInfoUri;} public void setUserInfoUri(String v){userInfoUri=v;}
    public String getUserNameAttributeName(){return userNameAttributeName;} public void setUserNameAttributeName(String v){userNameAttributeName=v;}
    public String getJwkSetUri(){return jwkSetUri;} public void setJwkSetUri(String v){jwkSetUri=v;}
    public String getClientAuthenticationMethod(){return clientAuthenticationMethod;} public void setClientAuthenticationMethod(String v){clientAuthenticationMethod=v;}
    public String getLoginButtonLabel(){return loginButtonLabel;} public void setLoginButtonLabel(String v){loginButtonLabel=v;}
    public String getLoginButtonIcon(){return loginButtonIcon;} public void setLoginButtonIcon(String v){loginButtonIcon=v;}
    public String getPlatforms(){return platforms;} public void setPlatforms(String v){platforms=v;}
    public Boolean getAllowUserCreation(){return allowUserCreation;} public void setAllowUserCreation(Boolean v){allowUserCreation=v;}
    public Boolean getActivateUser(){return activateUser;} public void setActivateUser(Boolean v){activateUser=v;}
    public String getType(){return type;} public void setType(String v){type=v;}
    public String getBasicEmailAttributeKey(){return basicEmailAttributeKey;} public void setBasicEmailAttributeKey(String v){basicEmailAttributeKey=v;}
    public String getBasicFirstNameAttributeKey(){return basicFirstNameAttributeKey;} public void setBasicFirstNameAttributeKey(String v){basicFirstNameAttributeKey=v;}
    public String getBasicLastNameAttributeKey(){return basicLastNameAttributeKey;} public void setBasicLastNameAttributeKey(String v){basicLastNameAttributeKey=v;}
    public String getBasicTenantNameStrategy(){return basicTenantNameStrategy;} public void setBasicTenantNameStrategy(String v){basicTenantNameStrategy=v;}
    public String getBasicTenantNamePattern(){return basicTenantNamePattern;} public void setBasicTenantNamePattern(String v){basicTenantNamePattern=v;}
    public String getBasicCustomerNamePattern(){return basicCustomerNamePattern;} public void setBasicCustomerNamePattern(String v){basicCustomerNamePattern=v;}
    public String getBasicDefaultDashboardName(){return basicDefaultDashboardName;} public void setBasicDefaultDashboardName(String v){basicDefaultDashboardName=v;}
    public Boolean getBasicAlwaysFullScreen(){return basicAlwaysFullScreen;} public void setBasicAlwaysFullScreen(Boolean v){basicAlwaysFullScreen=v;}
    public String getCustomUrl(){return customUrl;} public void setCustomUrl(String v){customUrl=v;}
    public String getCustomUsername(){return customUsername;} public void setCustomUsername(String v){customUsername=v;}
    public String getCustomPassword(){return customPassword;} public void setCustomPassword(String v){customPassword=v;}
    public Boolean getCustomSendToken(){return customSendToken;} public void setCustomSendToken(Boolean v){customSendToken=v;}
}
