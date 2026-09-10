package com.copenhagen.pgmock;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "pg.mock")
public record PgMockProperties(
        PgMockOutcome outcome,
        long responseDelayMillis) {

    public PgMockProperties {
        outcome = outcome == null ? PgMockOutcome.SUCCESS : outcome;
        if (responseDelayMillis < 0) {
            throw new IllegalArgumentException("pg.mock.response-delay-millis must not be negative");
        }
    }
}
