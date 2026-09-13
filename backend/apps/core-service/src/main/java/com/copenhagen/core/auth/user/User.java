package com.copenhagen.core.auth.user;

import java.time.Instant;
import java.util.UUID;

public record User(
        long id,
        UUID publicId,
        String name,
        UserStatus status,
        String email,
        boolean emailVerified,
        String phoneNumber,
        Instant deletedAt,
        Instant pwChangedAt,
        Instant lastLoginAt,
        Instant createdAt,
        Instant updatedAt) {
}
