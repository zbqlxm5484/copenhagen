package com.copenhagen.core.auth.user;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import com.copenhagen.core.common.crypto.EncryptedPii;

public interface UserRepository {

    /**
     * 이메일은 평문이 아니라 암호문과 조회용 해시로 받는다.
     * 호출자가 {@code PiiEncryptor}/{@code PiiHasher}로 변환한 뒤 넘긴다.
     */
    long insert(UUID publicId, String name, UserStatus status,
            EncryptedPii emailEncrypted, String emailHash, Instant now);

    Optional<User> findById(long id);

    Optional<User> findByPublicId(UUID publicId);
}
