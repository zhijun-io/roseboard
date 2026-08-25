package com.roseboard.common;

import java.util.UUID;

public interface Constants {
    UUID SYSTEM_TENANT_ID = new UUID(0, 0);

    String MFA_SETTINGS_KEY = "mfa";

    String CONNECTIVITY_SETTINGS_KEY = "connectivity";

    String SECURITY_SETTINGS_KEY = "security";

    String JWT_SETTINGS_KEY = "jwt";

    String GENERAL_SETTINGS_KEY = "general";

}
