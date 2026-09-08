package com.copenhagen.pay.common.auth;

import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "pay.auth")
public record PayAuthProperties(
        Map<String, String> userTokens,
        Map<String, String> serviceTokens) {

    public PayAuthProperties {
        userTokens = userTokens == null ? Map.of() : Map.copyOf(userTokens);
        serviceTokens = serviceTokens == null ? Map.of() : Map.copyOf(serviceTokens);
    }
}
