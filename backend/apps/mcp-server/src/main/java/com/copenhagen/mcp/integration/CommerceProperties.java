package com.copenhagen.mcp.integration;

import com.copenhagen.mcp.auth.AgentJwtProperties;
import java.net.URI;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("mcp.integration")
public record CommerceProperties(boolean enabled, URI appLinkBase) {
    public CommerceProperties {
        if (enabled) {
            AgentJwtProperties.requireSecureUri(appLinkBase);
            if (!"https".equals(appLinkBase.getScheme()) || !appLinkBase.getPath().endsWith("/")) {
                throw new IllegalArgumentException("App Link base must be HTTPS and end with a slash");
            }
        }
    }
}
