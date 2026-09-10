package com.copenhagen.pay.payment.credential.mandate;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "pay.mandate")
public record MandateProperties(
        String issuer,
        String keyId,
        String signingPrivateKey,
        String encryptionKey) {
}
