package com.roseboard.customer;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.Jackson3TypeHandler;
import com.baomidou.mybatisplus.annotation.Version;
import tools.jackson.databind.JsonNode;

import java.util.Map;
import java.util.UUID;

@TableName(value = "customer", autoResultMap = true)
public class CustomerEntity {
    @TableId(type = IdType.ASSIGN_UUID)
    private UUID id;
    private Long createdTime;
    @TableField(typeHandler = Jackson3TypeHandler.class)
    private JsonNode additionalInfo;
    private String address;
    private String address2;
    private String city;
    private String country;
    private String email;
    private String phone;
    private String state;
    private UUID tenantId;
    private String title;
    private String zip;
    private Boolean isPublic;
    private UUID externalId;
    public UUID getId() { return id; }
    @Version
    private Long version;

    public void setId(UUID id) { this.id = id; }
    public Long getCreatedTime() { return createdTime; }
    public void setCreatedTime(Long createdTime) { this.createdTime = createdTime; }
    public JsonNode getAdditionalInfo() { return additionalInfo; }
    public void setAdditionalInfo(JsonNode additionalInfo) { this.additionalInfo = additionalInfo; }
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
    public String getState() { return state; }
    public void setState(String state) { this.state = state; }
    public UUID getTenantId() { return tenantId; }
    public void setTenantId(UUID tenantId) { this.tenantId = tenantId; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getZip() { return zip; }
    public void setZip(String zip) { this.zip = zip; }
    public UUID getExternalId() { return externalId; }
    public void setExternalId(UUID externalId) { this.externalId = externalId; }
    public Boolean getIsPublic() { return isPublic; }
    public void setIsPublic(Boolean isPublic) { this.isPublic = isPublic; }
    public Long getVersion() { return version; }
    public void setVersion(Long version) { this.version = version; }
}
