package com.roseboard.user.credential;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.util.UUID;

@TableName("user_credential")
public class UserCredentialEntity {

    @TableId(type = IdType.ASSIGN_UUID)
    private UUID id;
    private Long createdTime;
    private String activateToken;
    private Long activateTokenExpTime;
    private Boolean enabled;
    private String password;
    private String resetToken;
    private Long resetTokenExpTime;
    private UUID userId;
    private String additionalInfo;
    private Long lastLoginTs;
    private Integer failedLoginAttempts;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public Long getCreatedTime() { return createdTime; }
    public void setCreatedTime(Long createdTime) { this.createdTime = createdTime; }
    public String getActivateToken() { return activateToken; }
    public void setActivateToken(String activateToken) { this.activateToken = activateToken; }
    public Long getActivateTokenExpTime() { return activateTokenExpTime; }
    public void setActivateTokenExpTime(Long activateTokenExpTime) { this.activateTokenExpTime = activateTokenExpTime; }
    public Boolean getEnabled() { return enabled; }
    public void setEnabled(Boolean enabled) { this.enabled = enabled; }
    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }
    public String getResetToken() { return resetToken; }
    public void setResetToken(String resetToken) { this.resetToken = resetToken; }
    public Long getResetTokenExpTime() { return resetTokenExpTime; }
    public void setResetTokenExpTime(Long resetTokenExpTime) { this.resetTokenExpTime = resetTokenExpTime; }
    public UUID getUserId() { return userId; }
    public void setUserId(UUID userId) { this.userId = userId; }
    public String getAdditionalInfo() { return additionalInfo; }
    public void setAdditionalInfo(String additionalInfo) { this.additionalInfo = additionalInfo; }
    public Long getLastLoginTs() { return lastLoginTs; }
    public void setLastLoginTs(Long lastLoginTs) { this.lastLoginTs = lastLoginTs; }
    public Integer getFailedLoginAttempts() { return failedLoginAttempts; }
    public void setFailedLoginAttempts(Integer failedLoginAttempts) { this.failedLoginAttempts = failedLoginAttempts; }
}
