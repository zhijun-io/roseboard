package com.roseboard.setting.oauth2.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.Jackson3TypeHandler;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.roseboard.setting.oauth2.client.OAuth2ClientEntity;
import tools.jackson.databind.JsonNode;

import java.util.List;
import java.util.UUID;

@TableName(value = "domain", autoResultMap = true)
public class DomainEntity {
    @TableId(type = IdType.ASSIGN_UUID)
    private UUID id;
    private Long createdTime;
    private UUID tenantId;
    private String name;
    private String description;
    @TableField(typeHandler = Jackson3TypeHandler.class)
    private JsonNode additionalInfo;
    private String oauth2ClientIds;
    @TableField(exist = false)
    @JsonIgnore
    private List<OAuth2ClientEntity> oauth2ClientInfos;

    public UUID getId(){return id;} public void setId(UUID v){id=v;}
    public Long getCreatedTime(){return createdTime;} public void setCreatedTime(Long v){createdTime=v;}
    public UUID getTenantId(){return tenantId;} public void setTenantId(UUID v){tenantId=v;}
    public String getName(){return name;} public void setName(String v){name=v;}
    public String getDescription(){return description;} public void setDescription(String v){description=v;}
    public JsonNode getAdditionalInfo(){return additionalInfo;} public void setAdditionalInfo(JsonNode v){additionalInfo=v;}
    public String getOauth2ClientIds(){return oauth2ClientIds;} public void setOauth2ClientIds(String v){oauth2ClientIds=v;}
    @JsonProperty("oauth2ClientInfos")
    public List<OAuth2ClientEntity> getOauth2ClientInfos(){return oauth2ClientInfos;}
    public void setOauth2ClientInfos(List<OAuth2ClientEntity> v){oauth2ClientInfos=v;}
}
