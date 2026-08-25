package com.roseboard.tenant.profile;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.roseboard.infrastructure.mybatis.Jackson3JsonbTypeHandler;
import tools.jackson.databind.JsonNode;

import java.util.UUID;

@TableName(value = "tenant_profile", autoResultMap = true)
public class TenantProfileEntity {
    @TableId(type = IdType.ASSIGN_UUID)
    private UUID id;
    private Long createdTime;
    private String name;
    @TableField(typeHandler = Jackson3JsonbTypeHandler.class,
            insertStrategy = FieldStrategy.NOT_NULL,
            updateStrategy = FieldStrategy.ALWAYS)
    private JsonNode profileData;
    private String description;
    private Boolean isDefault;
    private Boolean isolatedTbCore;
    private Boolean isolatedTbRuleEngine;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public Long getCreatedTime() { return createdTime; }
    public void setCreatedTime(Long createdTime) { this.createdTime = createdTime; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public JsonNode getProfileData() { return profileData; }
    public void setProfileData(JsonNode profileData) { this.profileData = profileData; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public Boolean getIsDefault() { return isDefault; }
    public void setIsDefault(Boolean isDefault) { this.isDefault = isDefault; }
    public Boolean getIsolatedTbCore() { return isolatedTbCore; }
    public void setIsolatedTbCore(Boolean isolatedTbCore) { this.isolatedTbCore = isolatedTbCore; }
    public Boolean getIsolatedTbRuleEngine() { return isolatedTbRuleEngine; }
    public void setIsolatedTbRuleEngine(Boolean isolatedTbRuleEngine) { this.isolatedTbRuleEngine = isolatedTbRuleEngine; }
}
