package com.roseboard.setting;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Min;

@JsonIgnoreProperties(ignoreUnknown = true)
public class SecuritySetting {
    @Valid
    private PasswordPolicy passwordPolicy = new PasswordPolicy();
    @Min(0)
    private Integer maxFailedLoginAttempts;
    @Email
    private String userLockoutNotificationEmail;
    @Min(0)
    private Integer mobileSecretKeyLength;
    @Min(0)
    private Integer userActivationTokenTtl;
    @Min(0)
    private Integer passwordResetTokenTtl;

    public PasswordPolicy getPasswordPolicy() { return passwordPolicy; }
    public void setPasswordPolicy(PasswordPolicy passwordPolicy) { this.passwordPolicy = passwordPolicy; }
    public Integer getMaxFailedLoginAttempts() { return maxFailedLoginAttempts; }
    public void setMaxFailedLoginAttempts(Integer value) { maxFailedLoginAttempts = value; }
    public String getUserLockoutNotificationEmail() { return userLockoutNotificationEmail; }
    public void setUserLockoutNotificationEmail(String value) { userLockoutNotificationEmail = value; }
    public Integer getMobileSecretKeyLength() { return mobileSecretKeyLength; }
    public void setMobileSecretKeyLength(Integer value) { mobileSecretKeyLength = value; }
    public Integer getUserActivationTokenTtl() { return userActivationTokenTtl; }
    public void setUserActivationTokenTtl(Integer value) { userActivationTokenTtl = value; }
    public Integer getPasswordResetTokenTtl() { return passwordResetTokenTtl; }
    public void setPasswordResetTokenTtl(Integer value) { passwordResetTokenTtl = value; }
}
