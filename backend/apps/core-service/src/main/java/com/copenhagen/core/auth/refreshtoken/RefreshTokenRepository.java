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

    /** {@code revoked_at IS NULL}인 활성 토큰만 대상으로 한다. 해당하는 행이 없으면 아무 일도 하지 않는다. */
    void revokeActiveByUserIdAndDeviceId(long userId, String deviceId, Instant now);
}
