package com.copenhagen.pay.fraud.config;

import java.math.BigDecimal;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Configuration only. Thresholds use the Sift API score scale [0, 1]. */
@ConfigurationProperties(prefix = "fraud.sift")
public record SiftFraudProperties(
        String apiKey,
        String accountId,
        String env,
        long timeoutSeconds,
        BigDecimal reviewThreshold,
        BigDecimal denyThreshold) {

    public SiftFraudProperties {
        apiKey = apiKey == null ? "" : apiKey;
        accountId = accountId == null ? "" : accountId;
        if (!"sandbox".equals(env) && !"production".equals(env)) {
            throw new IllegalArgumentException("fraud.sift.env must be sandbox or production");
        }
        if (timeoutSeconds <= 0) {
            throw new IllegalArgumentException("fraud.sift.timeout-seconds must be positive");
        }
        if ((reviewThreshold == null) != (denyThreshold == null)) {
            throw new IllegalArgumentException("Sift review and deny thresholds must be configured together");
        }
        if (reviewThreshold != null && (reviewThreshold.signum() < 0
                || denyThreshold.compareTo(BigDecimal.ONE) > 0
                || reviewThreshold.compareTo(denyThreshold) >= 0)) {
            throw new IllegalArgumentException("Sift thresholds must satisfy 0 <= review < deny <= 1");
        }
    }

    public Duration timeout() {
        return Duration.ofSeconds(timeoutSeconds);
    }

    @Override
    public String toString() {
        return "SiftFraudProperties[apiKey=<redacted>, accountId=<redacted>, env=" + env
                + ", timeoutSeconds=" + timeoutSeconds + ", reviewThreshold=" + reviewThreshold
                + ", denyThreshold=" + denyThreshold + "]";
    }
}
