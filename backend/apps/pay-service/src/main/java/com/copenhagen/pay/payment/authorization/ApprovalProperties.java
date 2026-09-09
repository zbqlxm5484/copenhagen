package com.copenhagen.pay.payment.authorization;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "pay.approval")
public record ApprovalProperties(long expirationSeconds) {

    public ApprovalProperties {
        if (expirationSeconds <= 0) {
            throw new IllegalArgumentException("pay.approval.expiration-seconds must be positive");
        }
    }
}
