package com.roseboard.tenant;

import com.baomidou.mybatisplus.annotation.*;
import com.baomidou.mybatisplus.extension.handlers.Jackson3TypeHandler;
import tools.jackson.databind.JsonNode;

import java.util.UUID;

@TableName(value = "tenant", autoResultMap = true)
public class TenantEntity {
    @TableId(type = IdType.ASSIGN_UUID)
    private UUID id;
    @TableField(exist = false)
    private String tenantProfileName;
    private Long createdTime;
    @TableField(typeHandler = Jackson3TypeHandler.class)
    private JsonNode additionalInfo;
    private UUID tenantProfileId;
    private String address;
    private String address2;
    private String city;
    private String country;
    private String email;
    private String phone;
    private String region;
    private String state;
    private String title;
    private String zip;
    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    @Version
    private Long version;

    public Long getCreatedTime() { return createdTime; }
    public void setCreatedTime(Long createdTime) { this.createdTime = createdTime; }
    public JsonNode getAdditionalInfo() { return additionalInfo; }
    public void setAdditionalInfo(JsonNode additionalInfo) { this.additionalInfo = additionalInfo; }
    public UUID getTenantProfileId() { return tenantProfileId; }
    public String getTenantProfileName() { return tenantProfileName; }
    public void setTenantProfileName(String tenantProfileName) { this.tenantProfileName = tenantProfileName; }
    public void setTenantProfileId(UUID tenantProfileId) { this.tenantProfileId = tenantProfileId; }
    public String getAddress() { return address; }
    public void setAddress(String address) { this.address = address; }
    public String getAddress2() { return address2; }
    public void setAddress2(String address2) { this.address2 = address2; }
    public String getCity() { return city; }
    public void setCity(String city) { this.city = city; }
    public String getCountry() { return country; }
    public void setCountry(String country) { this.country = country; }
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
    public String getPhone() { return phone; }
    public void setPhone(String phone) { this.phone = phone; }
    public String getRegion() { return region; }
    public void setRegion(String region) { this.region = region; }
    public String getState() { return state; }
    public void setState(String state) { this.state = state; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getZip() { return zip; }
    public void setZip(String zip) { this.zip = zip; }
    public Long getVersion() { return version; }
    public void setVersion(Long version) { this.version = version; }
}
