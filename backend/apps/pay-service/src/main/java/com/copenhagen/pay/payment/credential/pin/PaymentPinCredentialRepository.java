package com.copenhagen.pay.payment.credential.pin;

import java.time.Instant;
import java.util.Optional;

public interface PaymentPinCredentialRepository {

    Optional<PaymentPinCredential> findByUserIdForUpdate(String userId);

    void insert(
            String id,
            String userId,
            String pinHash,
            Instant updatedAt);

    void updatePin(
            String id,
            String pinHash,
            Instant updatedAt);

    void updateVerificationFailure(
            String id,
            int failedAttempts,
            Instant lockedUntil,
            PaymentPinStatus status,
            Instant updatedAt);

    void resetVerificationState(String id, Instant updatedAt);
}
