package com.roseboard.infrastructure.security.oauth2.model;

public class OAuth2MapperConfig {
    private boolean allowUserCreation;
    private boolean activateUser;
    private MapperType type;
    private OAuth2BasicMapperConfig basic;
    private OAuth2CustomMapperConfig custom;
    public boolean isAllowUserCreation(){return allowUserCreation;} public void setAllowUserCreation(boolean v){allowUserCreation=v;}
    public boolean isActivateUser(){return activateUser;} public void setActivateUser(boolean v){activateUser=v;}
    public MapperType getType(){return type;} public void setType(MapperType v){type=v;}
    public OAuth2BasicMapperConfig getBasic(){return basic;} public void setBasic(OAuth2BasicMapperConfig v){basic=v;}
    public OAuth2CustomMapperConfig getCustom(){return custom;} public void setCustom(OAuth2CustomMapperConfig v){custom=v;}
}
