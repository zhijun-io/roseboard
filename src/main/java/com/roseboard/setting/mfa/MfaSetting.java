package com.roseboard.setting.mfa;

import com.roseboard.infrastructure.security.jwt.mfa.MfaProviderType;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public class MfaSetting {
    private List<Provider> providers = new ArrayList<>();
    private boolean enforceTwoFa;
    private JsonNode enforcedUsersFilter;
    private int maxVerificationFailuresBeforeUserLockout;
    private int totalAllowedTimeForVerification = 1800;
    private boolean useSystemTwoFactorAuthSettings;
    private String verificationCodeCheckRateLimit;
    private int minVerificationCodeSendPeriod = 30;

    public List<Provider> getProviders() { return providers; }
    public void setProviders(List<Provider> providers) { this.providers = providers; }
    public boolean isEnforceTwoFa() { return enforceTwoFa; }
    public void setEnforceTwoFa(boolean enforceTwoFa) { this.enforceTwoFa = enforceTwoFa; }
    public JsonNode getEnforcedUsersFilter() { return enforcedUsersFilter; }
    public void setEnforcedUsersFilter(JsonNode value) { enforcedUsersFilter = value; }
    public int getMaxVerificationFailuresBeforeUserLockout() { return maxVerificationFailuresBeforeUserLockout; }
    public void setMaxVerificationFailuresBeforeUserLockout(int value) { maxVerificationFailuresBeforeUserLockout = value; }
    public int getTotalAllowedTimeForVerification() { return totalAllowedTimeForVerification; }
    public void setTotalAllowedTimeForVerification(int value) { totalAllowedTimeForVerification = value; }
    public boolean isUseSystemTwoFactorAuthSettings() { return useSystemTwoFactorAuthSettings; }
    public void setUseSystemTwoFactorAuthSettings(boolean value) { useSystemTwoFactorAuthSettings = value; }
    public String getVerificationCodeCheckRateLimit() { return verificationCodeCheckRateLimit; }
    public void setVerificationCodeCheckRateLimit(String value) { verificationCodeCheckRateLimit = value; }
    public int getMinVerificationCodeSendPeriod() { return minVerificationCodeSendPeriod; }
    public void setMinVerificationCodeSendPeriod(int value) { minVerificationCodeSendPeriod = value; }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Provider {
        private MfaProviderType providerType;
        private String issuerName;
        private String smsVerificationMessageTemplate;
        private Integer verificationCodeLifetime;

        public MfaProviderType getProviderType() { return providerType; }
        public void setProviderType(MfaProviderType providerType) { this.providerType = providerType; }
        public String getIssuerName() { return issuerName; }
        public void setIssuerName(String value) { issuerName = value; }
        public String getSmsVerificationMessageTemplate() { return smsVerificationMessageTemplate; }
        public void setSmsVerificationMessageTemplate(String value) { smsVerificationMessageTemplate = value; }
        public Integer getVerificationCodeLifetime() { return verificationCodeLifetime; }
        public void setVerificationCodeLifetime(Integer value) { verificationCodeLifetime = value; }
    }
}
