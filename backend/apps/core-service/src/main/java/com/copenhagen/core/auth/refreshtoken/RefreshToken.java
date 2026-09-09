package com.copenhagen.core.auth.refreshtoken;

import java.time.Instant;

public record RefreshToken(
        long id,
        long userId,
        String tokenHash,
        Instant expiresAt,
        Instant createdAt) {

    @Override
    public String toString() {
        return "RefreshToken["
                + "id=" + id
                + ", userId=" + userId
                + ", expiresAt=" + expiresAt
                + ", createdAt=" + createdAt
                + ']';
    }
}
