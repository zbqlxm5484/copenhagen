package com.copenhagen.pay.payment.credential.biometric;

import com.copenhagen.pay.common.error.PayErrorCode;
import com.copenhagen.pay.common.error.PayException;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BiometricCredentialService {

    private static final String PLATFORM_BIOMETRIC = "PLATFORM_BIOMETRIC";
    private static final int MAX_PUBLIC_KEY_LENGTH = 8192;

    private final BiometricCredentialRepository repository;
    private final Clock clock;

    public BiometricCredentialService(
            BiometricCredentialRepository repository,
            Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @Transactional
    public BiometricCredential register(
            String userId,
            String deviceId,
            String publicKey,
            String credentialType) {
        validate(deviceId, publicKey, credentialType);
        if (repository.findActiveByUserAndDevice(userId, deviceId).isPresent()) {
            throw new PayException(PayErrorCode.BIOMETRIC_CREDENTIAL_ALREADY_REGISTERED);
        }

        String credentialId = UUID.randomUUID().toString();
        Instant registeredAt = clock.instant();
        try {
            repository.insert(
                    credentialId,
                    userId,
                    deviceId,
                    publicKey,
                    registeredAt);
        } catch (DuplicateKeyException exception) {
            throw new PayException(
                    PayErrorCode.BIOMETRIC_CREDENTIAL_ALREADY_REGISTERED,
                    PayErrorCode.BIOMETRIC_CREDENTIAL_ALREADY_REGISTERED.defaultMessage(),
                    null,
                    PayErrorCode.BIOMETRIC_CREDENTIAL_ALREADY_REGISTERED.defaultNextAction(),
                    exception);
        }
        return new BiometricCredential(
                credentialId,
                userId,
                deviceId,
                publicKey,
                BiometricCredentialStatus.ACTIVE,
                registeredAt,
                null);
    }

    private void validate(
            String deviceId,
            String publicKey,
            String credentialType) {
        if (deviceId == null || deviceId.isBlank() || deviceId.length() > 150) {
            throw new PayException(PayErrorCode.BIOMETRIC_CREDENTIAL_INVALID);
        }
        if (!PLATFORM_BIOMETRIC.equals(credentialType)
                || publicKey == null
                || publicKey.isBlank()
                || publicKey.length() > MAX_PUBLIC_KEY_LENGTH) {
            throw new PayException(PayErrorCode.BIOMETRIC_CREDENTIAL_INVALID);
        }
        try {
            byte[] decodedPublicKey = Base64.getUrlDecoder().decode(publicKey);
            if (decodedPublicKey.length == 0) {
                throw new IllegalArgumentException("Public key is empty");
            }
        } catch (IllegalArgumentException exception) {
            throw new PayException(
                    PayErrorCode.BIOMETRIC_CREDENTIAL_INVALID,
                    PayErrorCode.BIOMETRIC_CREDENTIAL_INVALID.defaultMessage(),
                    null,
                    PayErrorCode.BIOMETRIC_CREDENTIAL_INVALID.defaultNextAction(),
                    exception);
        }
    }
}
