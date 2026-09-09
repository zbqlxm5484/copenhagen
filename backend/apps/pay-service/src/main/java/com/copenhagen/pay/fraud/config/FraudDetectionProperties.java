package com.copenhagen.pay.fraud.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Requested feature configuration. This flag does not enable a client or change payment behavior yet.
 * Future consumers must not interpret disabled detection as payment approval.
 */
@ConfigurationProperties(prefix = "fraud")
public record FraudDetectionProperties(boolean enabled) {
}
