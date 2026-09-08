package com.copenhagen.pay.common.auth;

import java.util.Optional;
import org.springframework.stereotype.Component;

@Component
public class ConfiguredBearerTokenVerifier implements PayTokenVerifier {

    private final PayAuthProperties properties;

    public ConfiguredBearerTokenVerifier(PayAuthProperties properties) {
        this.properties = properties;
    }

    @Override
    public Optional<PayPrincipal> verify(String bearerToken) {
        String userId = properties.userTokens().get(bearerToken);
        if (userId != null && !userId.isBlank()) {
            return Optional.of(new PayPrincipal(PayPrincipal.Type.USER, userId));
        }

        String serviceId = properties.serviceTokens().get(bearerToken);
        if (serviceId != null && !serviceId.isBlank()) {
            return Optional.of(new PayPrincipal(PayPrincipal.Type.SERVICE, serviceId));
        }

        return Optional.empty();
    }
}
