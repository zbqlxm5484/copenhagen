package com.copenhagen.pay.common.auth;

import java.util.Optional;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class ConfiguredBearerTokenVerifier implements PayTokenVerifier {

    private final PayAuthProperties properties;
    private final JwtBearerTokenVerifier jwtTokenVerifier;

    public ConfiguredBearerTokenVerifier(PayAuthProperties properties) {
        this.properties = properties;
        this.jwtTokenVerifier = new JwtBearerTokenVerifier(
                new PayJwtProperties(false, "", "", "", 0),
                Clock.systemUTC());
    }

    @Autowired
    public ConfiguredBearerTokenVerifier(
            PayAuthProperties properties,
            JwtBearerTokenVerifier jwtTokenVerifier) {
        this.properties = properties;
        this.jwtTokenVerifier = jwtTokenVerifier;
    }

    @Override
    public Optional<PayPrincipal> verify(String bearerToken) {
        String serviceId = properties.serviceTokens().get(bearerToken);
        if (serviceId != null && !serviceId.isBlank()) {
            return Optional.of(new PayPrincipal(PayPrincipal.Type.SERVICE, serviceId));
        }

        if (jwtTokenVerifier.enabled()) {
            return jwtTokenVerifier.verify(bearerToken);
        }

        String userId = properties.userTokens().get(bearerToken);
        if (userId != null && !userId.isBlank()) {
            return Optional.of(new PayPrincipal(PayPrincipal.Type.USER, userId));
        }

        return Optional.empty();
    }
}
