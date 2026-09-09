package com.copenhagen.pay.payment.credential.biometric;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.copenhagen.pay.common.error.PayErrorCode;
import com.copenhagen.pay.common.error.PayException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class BiometricCredentialServiceTest {

    @Test
    void registersPublicKeyWithoutReceivingBiometricRawData() {
        InMemoryBiometricCredentialRepository repository =
                new InMemoryBiometricCredentialRepository();
        BiometricCredentialService service = new BiometricCredentialService(
                repository,
                Clock.fixed(Instant.parse("2026-09-09T00:00:00Z"), ZoneOffset.UTC));
        String publicKey = Base64.getUrlEncoder().withoutPadding().encodeToString(
                "public-key-bytes".getBytes());

        BiometricCredential credential = service.register(
                "user-001",
                "device-001",
                publicKey,
                "PLATFORM_BIOMETRIC");

        assertEquals(publicKey, credential.publicKey());
        assertEquals(BiometricCredentialStatus.ACTIVE, credential.status());
        assertEquals(publicKey, repository.credentials().get(credential.id()).publicKey());
    }

    @Test
    void rejectsInvalidPublicKeyEncoding() {
        BiometricCredentialService service = new BiometricCredentialService(
                new InMemoryBiometricCredentialRepository(),
                Clock.systemUTC());

        PayException exception = assertThrows(
                PayException.class,
                () -> service.register(
                        "user-001",
                        "device-001",
                        "not-base64-url!",
                        "PLATFORM_BIOMETRIC"));

        assertEquals(PayErrorCode.BIOMETRIC_CREDENTIAL_INVALID, exception.errorCode());
    }

    @Test
    void rejectsDuplicateActiveDeviceCredential() {
        InMemoryBiometricCredentialRepository repository =
                new InMemoryBiometricCredentialRepository();
        BiometricCredentialService service = new BiometricCredentialService(
                repository,
                Clock.systemUTC());
        String publicKey = Base64.getUrlEncoder().withoutPadding().encodeToString(
                "public-key-bytes".getBytes());

        service.register("user-001", "device-001", publicKey, "PLATFORM_BIOMETRIC");
        PayException exception = assertThrows(
                PayException.class,
                () -> service.register(
                        "user-001",
                        "device-001",
                        publicKey,
                        "PLATFORM_BIOMETRIC"));

        assertEquals(
                PayErrorCode.BIOMETRIC_CREDENTIAL_ALREADY_REGISTERED,
                exception.errorCode());
    }

    private static final class InMemoryBiometricCredentialRepository
            implements BiometricCredentialRepository {

        private final Map<String, BiometricCredential> credentials = new HashMap<>();

        @Override
        public Optional<BiometricCredential> findActiveByUserAndDevice(
                String userId,
                String deviceId) {
            return credentials.values().stream()
                    .filter(credential -> credential.userId().equals(userId))
                    .filter(credential -> credential.deviceId().equals(deviceId))
                    .filter(credential -> credential.status() == BiometricCredentialStatus.ACTIVE)
                    .findFirst();
        }

        @Override
        public void insert(
                String id,
                String userId,
                String deviceId,
                String publicKey,
                Instant registeredAt) {
            credentials.put(
                    id,
                    new BiometricCredential(
                            id,
                            userId,
                            deviceId,
                            publicKey,
                            BiometricCredentialStatus.ACTIVE,
                            registeredAt,
                            null));
        }

        private Map<String, BiometricCredential> credentials() {
            return credentials;
        }
    }
}
