package com.copenhagen.core.auth.refreshtoken;

import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RefreshTokenServiceTest {

    private static final Instant BASE_INSTANT = Instant.parse("2026-01-01T00:00:00Z");
    private static final long USER_ID = 1L;

    private FakeRefreshTokenRepository repository;
    private RefreshTokenService service;

    @BeforeEach
    void setUp() {
        repository = new FakeRefreshTokenRepository();
        service = new RefreshTokenService(repository, Clock.fixed(BASE_INSTANT, ZoneOffset.UTC));
    }

    @Test
    @DisplayName("신규 발급 시 평문 토큰과 만료 시각(14일 뒤)을 반환하고, 저장값은 해시다")
    void issue_newToken_returnsPlainTokenAndPersistsHashOnly() throws Exception {
        IssuedRefreshToken issued = service.issue(USER_ID, "device-1", "iPhone 15");

        assertNotNull(issued.plainToken());
        assertEquals(BASE_INSTANT.plus(Duration.ofDays(14)), issued.expiresAt());

        RefreshToken saved = repository.rows.get(0);
        assertEquals(sha256Hex(issued.plainToken()), saved.tokenHash());
        assertNotEquals(issued.plainToken(), saved.tokenHash());
    }

    @Test
    @DisplayName("같은 (user, device)로 재로그인하면 이전 활성 토큰을 폐기하고 새 행을 추가한다")
    void issue_sameUserAndDevice_revokesPreviousActiveToken() {
        service.issue(USER_ID, "device-1", "iPhone 15");
        service.issue(USER_ID, "device-1", "iPhone 15");

        assertEquals(2, repository.rows.size());
        assertTrue(repository.revokedCalls.contains(USER_ID + ":device-1"));
    }

    @Test
    @DisplayName("서로 다른 device는 독립적인 세션을 가진다(폐기하지 않음)")
    void issue_differentDevice_doesNotRevokeOtherDeviceToken() {
        service.issue(USER_ID, "device-1", "iPhone 15");
        service.issue(USER_ID, "device-2", "iPad");

        assertEquals(2, repository.rows.size());
    }

    private static String sha256Hex(String value) throws Exception {
        byte[] hash = MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(hash);
    }

    private static class FakeRefreshTokenRepository implements RefreshTokenRepository {

        private final List<RefreshToken> rows = new ArrayList<>();
        private final List<String> revokedCalls = new ArrayList<>();
        private long nextId = 1;

        @Override
        public long insert(
                long userId, String tokenHash, String deviceId, String deviceName, Instant expiresAt, Instant now) {
            long id = nextId++;
            rows.add(new RefreshToken(id, userId, tokenHash, deviceId, deviceName, now, expiresAt, null, null, now));
            return id;
        }

        @Override
        public Optional<RefreshToken> findByTokenHash(String tokenHash) {
            return rows.stream().filter(r -> r.tokenHash().equals(tokenHash)).findFirst();
        }

        @Override
        public int deleteByTokenHash(String tokenHash) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void revokeActiveByUserIdAndDeviceId(long userId, String deviceId, Instant now) {
            revokedCalls.add(userId + ":" + deviceId);
            for (int i = 0; i < rows.size(); i++) {
                RefreshToken row = rows.get(i);
                if (row.userId() == userId && row.deviceId().equals(deviceId) && row.revokedAt() == null) {
                    rows.set(i, new RefreshToken(row.id(), row.userId(), row.tokenHash(), row.deviceId(),
                            row.deviceName(), row.issuedAt(), row.expiresAt(), now, row.lastUsedAt(), row.createdAt()));
                }
            }
        }
    }
}
