package com.roseboard.infrastructure.security.oauth2.model;

public class OAuth2BasicMapperConfig {
    private String emailAttributeKey;
    private String firstNameAttributeKey;
    private String lastNameAttributeKey;
    private TenantNameStrategyType tenantNameStrategy;
    private String tenantNamePattern;
    private String customerNamePattern;
    private String defaultDashboardName;
    private boolean alwaysFullScreen;
    public String getEmailAttributeKey(){return emailAttributeKey;} public void setEmailAttributeKey(String v){emailAttributeKey=v;}
    public String getFirstNameAttributeKey(){return firstNameAttributeKey;} public void setFirstNameAttributeKey(String v){firstNameAttributeKey=v;}
    public String getLastNameAttributeKey(){return lastNameAttributeKey;} public void setLastNameAttributeKey(String v){lastNameAttributeKey=v;}
    public TenantNameStrategyType getTenantNameStrategy(){return tenantNameStrategy;} public void setTenantNameStrategy(TenantNameStrategyType v){tenantNameStrategy=v;}
    public String getTenantNamePattern(){return tenantNamePattern;} public void setTenantNamePattern(String v){tenantNamePattern=v;}
    public String getCustomerNamePattern(){return customerNamePattern;} public void setCustomerNamePattern(String v){customerNamePattern=v;}
    public String getDefaultDashboardName(){return defaultDashboardName;} public void setDefaultDashboardName(String v){defaultDashboardName=v;}
    public boolean isAlwaysFullScreen(){return alwaysFullScreen;} public void setAlwaysFullScreen(boolean v){alwaysFullScreen=v;}
}
