package com.copenhagen.core.auth.user;

import java.time.Instant;
import java.util.UUID;

public record User(
        long id,
        UUID publicId,
        String name,
        UserStatus status,
        Instant createdAt,
        Instant updatedAt) {
}
