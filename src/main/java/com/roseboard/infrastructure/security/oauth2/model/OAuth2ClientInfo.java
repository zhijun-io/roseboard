package com.roseboard.infrastructure.security.oauth2.model;

import java.util.List;
import java.util.UUID;

public class OAuth2ClientInfo {
 private UUID id;
 private Long createdTime;
 private String title;
 private String providerName;
 private List<PlatformType> platforms;
 public UUID getId(){return id;} public void setId(UUID v){id=v;}
 public Long getCreatedTime(){return createdTime;} public void setCreatedTime(Long v){createdTime=v;}
 public String getTitle(){return title;} public void setTitle(String v){title=v;}
 public String getProviderName(){return providerName;} public void setProviderName(String v){providerName=v;}
 public List<PlatformType> getPlatforms(){return platforms;} public void setPlatforms(List<PlatformType> v){platforms=v;}
}
