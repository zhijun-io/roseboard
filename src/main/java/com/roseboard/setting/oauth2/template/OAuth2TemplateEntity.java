package com.roseboard.setting.oauth2.template;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.Jackson3TypeHandler;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import tools.jackson.databind.JsonNode;

import java.util.UUID;
import java.util.Arrays;
import java.util.List;

@TableName(value = "oauth2_client_registration_template", autoResultMap = true)
public class OAuth2TemplateEntity {
    @TableId(type = IdType.ASSIGN_UUID) private UUID id;
    private Long createdTime;
    @TableField(typeHandler = Jackson3TypeHandler.class) private JsonNode additionalInfo;
    private String providerId;
    private String authorizationUri;
    @TableField("token_uri") private String accessTokenUri;
    private String scope;
    private String userInfoUri;
    private String userNameAttributeName;
    private String jwkSetUri;
    private String clientAuthenticationMethod;
    private String type;
    private String comment;
    private String loginButtonIcon;
    private String loginButtonLabel;
    private String helpLink;

    public UUID getId(){return id;} public void setId(UUID v){id=v;}
    public Long getCreatedTime(){return createdTime;} public void setCreatedTime(Long v){createdTime=v;}
    public JsonNode getAdditionalInfo(){return additionalInfo;} public void setAdditionalInfo(JsonNode v){additionalInfo=v;}
    public String getProviderId(){return providerId;} public void setProviderId(String v){providerId=v;}
    public String getAuthorizationUri(){return authorizationUri;} public void setAuthorizationUri(String v){authorizationUri=v;}
    public String getAccessTokenUri(){return accessTokenUri;} public void setAccessTokenUri(String v){accessTokenUri=v;}
    @JsonProperty("name") public String getName(){return loginButtonLabel == null || loginButtonLabel.isBlank() ? providerId : loginButtonLabel;}
    @JsonIgnore public String getScope(){return scope;}
    @JsonProperty("scope") public List<String> getJsonScope(){return scope == null || scope.isBlank() ? List.of() : Arrays.asList(scope.split("\\s+"));}
    public void setScope(String v){scope=v;}
    @JsonProperty("scope") public void setJsonScope(List<String> v){scope=v == null ? null : String.join(" ", v);}
    public String getUserInfoUri(){return userInfoUri;} public void setUserInfoUri(String v){userInfoUri=v;}
    public String getUserNameAttributeName(){return userNameAttributeName;} public void setUserNameAttributeName(String v){userNameAttributeName=v;}
    public String getJwkSetUri(){return jwkSetUri;} public void setJwkSetUri(String v){jwkSetUri=v;}
    public String getClientAuthenticationMethod(){return clientAuthenticationMethod;} public void setClientAuthenticationMethod(String v){clientAuthenticationMethod=v;}
    public String getType(){return type;} public void setType(String v){type=v;}
    public String getComment(){return comment;} public void setComment(String v){comment=v;}
    public String getLoginButtonIcon(){return loginButtonIcon;} public void setLoginButtonIcon(String v){loginButtonIcon=v;}
    public String getLoginButtonLabel(){return loginButtonLabel;} public void setLoginButtonLabel(String v){loginButtonLabel=v;}
    public String getHelpLink(){return helpLink;} public void setHelpLink(String v){helpLink=v;}
}
