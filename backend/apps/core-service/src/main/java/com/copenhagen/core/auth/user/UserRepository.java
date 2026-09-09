package com.copenhagen.core.auth.user;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface UserRepository {

    long insert(UUID publicId, String name, UserStatus status, Instant now);

    Optional<User> findById(long id);

    Optional<User> findByPublicId(UUID publicId);
}
