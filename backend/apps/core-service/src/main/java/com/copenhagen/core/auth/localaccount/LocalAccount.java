package com.copenhagen.core.auth.localaccount;

import java.time.Instant;

public record LocalAccount(
        long id,
        long userId,
        String loginId,
        String passwordHash,
        Instant passwordChangedAt,
        int failedLoginAttempts,
        Instant lastFailedLoginAt,
        Instant lockedUntil,
        Instant lastLoginAt,
        Instant createdAt,
        Instant updatedAt) {

    @Override
    public String toString() {
        return "LocalAccount["
                + "id=" + id
                + ", userId=" + userId
                + ", loginId='" + loginId + '\''
                + ", passwordChangedAt=" + passwordChangedAt
                + ", failedLoginAttempts=" + failedLoginAttempts
                + ", lastFailedLoginAt=" + lastFailedLoginAt
                + ", lockedUntil=" + lockedUntil
                + ", lastLoginAt=" + lastLoginAt
                + ", createdAt=" + createdAt
                + ", updatedAt=" + updatedAt
                + ']';
    }
}
