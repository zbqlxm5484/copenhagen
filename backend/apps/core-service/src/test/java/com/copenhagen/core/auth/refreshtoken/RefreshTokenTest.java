package com.copenhagen.core.auth.refreshtoken;

import java.time.Instant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RefreshTokenTest {

    @Test
    @DisplayName("toString()은 token_hash 원문을 노출하지 않는다")
    void toString_doesNotExposeTokenHash() {
        RefreshToken refreshToken = new RefreshToken(1L, 1L, "secret-token-hash", Instant.EPOCH, Instant.EPOCH);

        String result = refreshToken.toString();

        assertFalse(result.contains("secret-token-hash"));
        assertTrue(result.contains("id=1"));
    }
}
