package com.copenhagen.core.auth.localaccount;

import java.time.Instant;
import java.util.Optional;

public interface LocalAccountRepository {

    boolean existsByLoginId(String loginId);

    Optional<LocalAccount> findByLoginId(String loginId);

    long insert(long userId, String loginId, String passwordHash, Instant now);
}
