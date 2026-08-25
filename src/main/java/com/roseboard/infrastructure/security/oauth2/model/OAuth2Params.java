package com.roseboard.infrastructure.security.oauth2.model;

import java.util.UUID;

public class OAuth2Params {
    private UUID id;
    private boolean enabled;
    private boolean edgeEnabled;
    private UUID tenantId;
    public UUID getId(){return id;} public void setId(UUID v){id=v;}
    public boolean isEnabled(){return enabled;} public void setEnabled(boolean v){enabled=v;}
    public boolean isEdgeEnabled(){return edgeEnabled;} public void setEdgeEnabled(boolean v){edgeEnabled=v;}
    public UUID getTenantId(){return tenantId;} public void setTenantId(UUID v){tenantId=v;}
}
