package com.copenhagen.pay.payment.credential.biometric;

import java.time.Instant;

public record BiometricChallenge(
        String id,
        String biometricCredentialId,
        String authorizationSessionId,
        String challengeHash,
        BiometricChallengeStatus status,
        Instant expiresAt,
        Instant usedAt,
        Instant createdAt) {
}
