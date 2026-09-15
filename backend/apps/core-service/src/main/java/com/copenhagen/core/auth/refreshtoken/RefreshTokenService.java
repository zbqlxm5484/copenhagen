package com.copenhagen.core.auth.refreshtoken;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 로그인 성공 시 Refresh Token을 발급한다. 정책은 §11.2.1·§11.2.5 확정 사항 그대로다. */
@Service
public class RefreshTokenService {

    static final Duration REFRESH_TOKEN_TTL = Duration.ofDays(14);
    private static final int TOKEN_BYTES = 32;

    private final RefreshTokenRepository repository;
    private final Clock clock;
    private final SecureRandom secureRandom = new SecureRandom();

    public RefreshTokenService(RefreshTokenRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @Transactional
    public IssuedRefreshToken issue(long userId, String deviceId, String deviceName) {
        Instant now = clock.instant();
        repository.revokeActiveByUserIdAndDeviceId(userId, deviceId, now);

        byte[] randomBytes = new byte[TOKEN_BYTES];
        secureRandom.nextBytes(randomBytes);
        String plainToken = Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes);
        String tokenHash = sha256Hex(plainToken);
        Instant expiresAt = now.plus(REFRESH_TOKEN_TTL);

        repository.insert(userId, tokenHash, deviceId, deviceName, expiresAt, now);
        return new IssuedRefreshToken(plainToken, expiresAt);
    }

    private static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 algorithm unavailable", exception);
        }
    }
}
