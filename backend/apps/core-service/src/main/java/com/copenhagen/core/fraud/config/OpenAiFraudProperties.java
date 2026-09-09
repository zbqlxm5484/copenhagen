package com.copenhagen.core.fraud.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Configuration only; no OpenAI client is registered by this type. */
@ConfigurationProperties(prefix = "fraud.openai")
public record OpenAiFraudProperties(
        String apiKey,
        String model,
        long timeoutSeconds) {

    public OpenAiFraudProperties {
        apiKey = apiKey == null ? "" : apiKey;
        if (model == null || model.isBlank()) {
            throw new IllegalArgumentException("fraud.openai.model must not be blank");
        }
        if (timeoutSeconds <= 0) {
            throw new IllegalArgumentException("fraud.openai.timeout-seconds must be positive");
        }
    }

    public Duration timeout() {
        return Duration.ofSeconds(timeoutSeconds);
    }

    @Override
    public String toString() {
        return "OpenAiFraudProperties[apiKey=<redacted>, model=" + model
                + ", timeoutSeconds=" + timeoutSeconds + "]";
    }
}
