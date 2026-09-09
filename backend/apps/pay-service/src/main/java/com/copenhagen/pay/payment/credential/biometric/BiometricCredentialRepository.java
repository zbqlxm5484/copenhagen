package com.copenhagen.pay.payment.credential.biometric;

import java.time.Instant;
import java.util.Optional;

public interface BiometricCredentialRepository {

    Optional<BiometricCredential> findActiveByUserAndDevice(
            String userId,
            String deviceId);

    void insert(
            String id,
            String userId,
            String deviceId,
            String publicKey,
            Instant registeredAt);
}
