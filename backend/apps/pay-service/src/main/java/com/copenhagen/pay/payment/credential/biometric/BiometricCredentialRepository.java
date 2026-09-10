package com.copenhagen.pay.payment.credential.biometric;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface BiometricCredentialRepository {

    Optional<BiometricCredential> findActiveByUserAndDevice(
            String userId,
            String deviceId);

    default List<BiometricCredential> findActiveByUser(String userId) {
        return List.of();
    }

    default Optional<BiometricCredential> findActiveByIdAndUser(
            String credentialId,
            String userId) {
        return Optional.empty();
    }

    void insert(
            String id,
            String userId,
            String deviceId,
            String publicKey,
            Instant registeredAt);
}
