package com.copenhagen.pay.payment.credential.pin;

import java.time.Instant;

public record PaymentPinCredential(
        String id,
        String userId,
        String pinHash,
        int failedAttempts,
        Instant lockedUntil,
        PaymentPinStatus status) {

    @Override
    public String toString() {
        return "PaymentPinCredential["
                + "id='" + id + '\''
                + ", userId='" + userId + '\''
                + ", failedAttempts=" + failedAttempts
                + ", lockedUntil=" + lockedUntil
                + ", status=" + status
                + ']';
    }
}
