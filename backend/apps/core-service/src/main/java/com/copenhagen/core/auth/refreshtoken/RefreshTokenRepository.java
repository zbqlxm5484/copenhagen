package com.copenhagen.core.auth.refreshtoken;

import java.time.Instant;
import java.util.Optional;

public interface RefreshTokenRepository {

    long insert(
            long userId,
            String tokenHash,
            String deviceId,
            String deviceName,
            Instant expiresAt,
            Instant now);

    Optional<RefreshToken> findByTokenHash(String tokenHash);

    int deleteByTokenHash(String tokenHash);
}
