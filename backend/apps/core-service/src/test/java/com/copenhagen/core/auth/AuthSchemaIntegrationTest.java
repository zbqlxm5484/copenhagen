package com.copenhagen.core.auth;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.copenhagen.core.auth.localaccount.LocalAccount;
import com.copenhagen.core.auth.localaccount.LocalAccountRepository;
import com.copenhagen.core.auth.oauthaccount.OauthAccount;
import com.copenhagen.core.auth.oauthaccount.OauthAccountRepository;
import com.copenhagen.core.auth.oauthaccount.OauthProvider;
import com.copenhagen.core.auth.refreshtoken.RefreshToken;
import com.copenhagen.core.auth.refreshtoken.RefreshTokenRepository;
import com.copenhagen.core.auth.user.User;
import com.copenhagen.core.auth.user.UserRepository;
import com.copenhagen.core.auth.user.UserStatus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@EnabledIfEnvironmentVariable(named = "CORE_TEST_DB_URL", matches = ".+")
class AuthSchemaIntegrationTest {

    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
    private static final Instant NOW = FIXED_CLOCK.instant();

    @DynamicPropertySource
    static void coreTestDataSource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> System.getenv("CORE_TEST_DB_URL"));
        registry.add("spring.datasource.username", () -> System.getenv("CORE_TEST_DB_USER"));
        registry.add("spring.datasource.password", () -> System.getenv("CORE_TEST_DB_PASSWORD"));
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private LocalAccountRepository localAccountRepository;

    @Autowired
    private OauthAccountRepository oauthAccountRepository;

    @Autowired
    private RefreshTokenRepository refreshTokenRepository;

    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate.update("TRUNCATE refresh_token, oauth_account, local_account, users RESTART IDENTITY CASCADE");
    }

    @Test
    @DisplayName("네 테이블 모두 저장한 값을 그대로 조회할 수 있다")
    void repositories_insertThenFind_returnsSameValues() {
        long userId = insertUser("user1@example.com");
        UUID publicId = userRepository.findById(userId).orElseThrow().publicId();

        long localAccountId = localAccountRepository.insert(userId, "user1", "hashed-password", NOW);
        long oauthAccountId = oauthAccountRepository.insert(userId, OauthProvider.GOOGLE, "google-subject-1", NOW);
        long refreshTokenId = refreshTokenRepository.insert(
                userId, tokenHash("token-1"), "device-1", "Test Device", NOW.plusSeconds(3600), NOW);

        User user = userRepository.findByPublicId(publicId).orElseThrow();
        assertEquals(userId, user.id());
        assertEquals(UserStatus.ACTIVE, user.status());
        assertEquals("user1@example.com", user.email());
        assertFalse(user.emailVerified());
        assertNull(user.phoneNumber());

        LocalAccount localAccount = localAccountRepository.findByLoginId("user1").orElseThrow();
        assertEquals(localAccountId, localAccount.id());
        assertEquals(userId, localAccount.userId());
        assertEquals("hashed-password", localAccount.passwordHash());
        assertEquals(0, localAccount.failedLoginCount());
        assertNull(localAccount.lastFailedLoginAt());
        assertNull(localAccount.lockedUntil());

        OauthAccount oauthAccount = oauthAccountRepository
                .findByProviderAndProviderSubject(OauthProvider.GOOGLE, "google-subject-1")
                .orElseThrow();
        assertEquals(oauthAccountId, oauthAccount.id());
        assertEquals(userId, oauthAccount.userId());
        assertEquals(OauthProvider.GOOGLE, oauthAccount.provider());

        RefreshToken refreshToken = refreshTokenRepository.findByTokenHash(tokenHash("token-1")).orElseThrow();
        assertEquals(refreshTokenId, refreshToken.id());
        assertEquals(userId, refreshToken.userId());
        assertEquals("device-1", refreshToken.deviceId());
        assertEquals("Test Device", refreshToken.deviceName());
        assertNull(refreshToken.revokedAt());
        assertNull(refreshToken.lastUsedAt());
    }

    @Test
    @DisplayName("사용자 1명에 자체 로그인 계정은 하나만 연결할 수 있다")
    void localAccount_secondForSameUser_violatesUniqueConstraint() {
        long userId = insertUser("user2@example.com");
        localAccountRepository.insert(userId, "first-login", "hash-1", NOW);

        assertThrows(DuplicateKeyException.class,
                () -> localAccountRepository.insert(userId, "second-login", "hash-2", NOW));
    }

    @Test
    @DisplayName("사용자 1명은 서로 다른 OAuth 계정을 여러 개 연결할 수 있다")
    void oauthAccount_multipleUsers_eachSucceeds() {
        long firstUserId = insertUser("user3@example.com");
        long secondUserId = insertUser("user4@example.com");

        assertTrue(oauthAccountRepository.insert(firstUserId, OauthProvider.GOOGLE, "subject-a", NOW) > 0);
        assertTrue(oauthAccountRepository.insert(secondUserId, OauthProvider.GOOGLE, "subject-b", NOW) > 0);
    }

    @Test
    @DisplayName("사용자 1명에 리프레시 토큰을 여러 개 저장할 수 있다")
    void refreshToken_multipleForSameUser_bothSucceed() {
        long userId = insertUser("user5@example.com");

        refreshTokenRepository.insert(userId, tokenHash("token-a"), "device-a", "Device A", NOW.plusSeconds(60), NOW);
        refreshTokenRepository.insert(userId, tokenHash("token-b"), "device-b", "Device B", NOW.plusSeconds(120), NOW);

        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM refresh_token WHERE user_id = ?", Integer.class, userId);
        assertEquals(2, count);
    }

    @Test
    @DisplayName("login_id는 중복 저장할 수 없다")
    void localAccount_duplicateLoginId_violatesUniqueConstraint() {
        long firstUserId = insertUser("user6@example.com");
        long secondUserId = insertUser("user7@example.com");
        localAccountRepository.insert(firstUserId, "duplicate-id", "hash-1", NOW);

        assertThrows(DuplicateKeyException.class,
                () -> localAccountRepository.insert(secondUserId, "duplicate-id", "hash-2", NOW));

        assertFalse(localAccountRepository.existsByLoginId("duplicate-id-2"));
    }

    @Test
    @DisplayName("public_id는 중복 저장할 수 없다")
    void user_duplicatePublicId_violatesUniqueConstraint() {
        UUID publicId = UUID.randomUUID();
        userRepository.insert(publicId, "first", UserStatus.ACTIVE, "duplicate-public-id-1@example.com", NOW);

        assertThrows(DuplicateKeyException.class,
                () -> userRepository.insert(
                        publicId, "second", UserStatus.ACTIVE, "duplicate-public-id-2@example.com", NOW));
    }

    @Test
    @DisplayName("email은 중복 저장할 수 없다")
    void user_duplicateEmail_violatesUniqueConstraint() {
        String email = "duplicate-email@example.com";
        userRepository.insert(UUID.randomUUID(), "first", UserStatus.ACTIVE, email, NOW);

        assertThrows(DuplicateKeyException.class,
                () -> userRepository.insert(UUID.randomUUID(), "second", UserStatus.ACTIVE, email, NOW));
    }

    @Test
    @DisplayName("phone_number는 둘 다 값이 있으면 중복 저장할 수 없다")
    void user_duplicatePhoneNumber_violatesUniqueConstraint() {
        long firstUserId = insertUser("phone1@example.com");
        String phoneNumber = "+821012345678";
        jdbcTemplate.update("UPDATE users SET phone_number = ? WHERE id = ?", phoneNumber, firstUserId);

        long secondUserId = insertUser("phone2@example.com");

        assertThrows(DataIntegrityViolationException.class, () -> jdbcTemplate.update(
                "UPDATE users SET phone_number = ? WHERE id = ?", phoneNumber, secondUserId));
    }

    @Test
    @DisplayName("phone_number는 NULL이면 여러 사용자가 동시에 가질 수 있다")
    void user_phoneNumberBothNull_succeeds() {
        insertUser("phone-null-1@example.com");
        insertUser("phone-null-2@example.com");

        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM users WHERE phone_number IS NULL", Integer.class);
        assertEquals(2, count);
    }

    @Test
    @DisplayName("동일 provider와 provider_subject 조합은 중복 저장할 수 없다")
    void oauthAccount_duplicateProviderAndSubject_violatesUniqueConstraint() {
        long firstUserId = insertUser("user8@example.com");
        long secondUserId = insertUser("user9@example.com");
        oauthAccountRepository.insert(firstUserId, OauthProvider.GOOGLE, "shared-subject", NOW);

        assertThrows(DuplicateKeyException.class,
                () -> oauthAccountRepository.insert(secondUserId, OauthProvider.GOOGLE, "shared-subject", NOW));
    }

    @Test
    @DisplayName("한 사용자가 같은 provider로 OAuth 계정을 두 번 연결할 수 없다")
    void oauthAccount_sameUserSameProviderTwice_violatesUniqueConstraint() {
        long userId = insertUser("user10@example.com");
        oauthAccountRepository.insert(userId, OauthProvider.GOOGLE, "subject-1", NOW);

        assertThrows(DuplicateKeyException.class,
                () -> oauthAccountRepository.insert(userId, OauthProvider.GOOGLE, "subject-2", NOW));
    }

    @Test
    @DisplayName("token_hash는 중복 저장할 수 없다")
    void refreshToken_duplicateTokenHash_violatesUniqueConstraint() {
        long firstUserId = insertUser("user11@example.com");
        long secondUserId = insertUser("user12@example.com");
        refreshTokenRepository.insert(
                firstUserId, tokenHash("shared-token"), "device-1", "Device 1", NOW.plusSeconds(60), NOW);

        assertThrows(DuplicateKeyException.class,
                () -> refreshTokenRepository.insert(
                        secondUserId, tokenHash("shared-token"), "device-2", "Device 2", NOW.plusSeconds(60), NOW));
    }

    @Test
    @DisplayName("정의되지 않은 users.status 값은 저장할 수 없다")
    void user_invalidStatus_violatesCheckConstraint() {
        assertThrows(DataIntegrityViolationException.class, () -> jdbcTemplate.update(
                """
                INSERT INTO users (public_id, name, status, email, created_at, updated_at)
                VALUES (?, ?, 'DELETED', ?, ?, ?)
                """,
                UUID.randomUUID(), "invalid-status-user", "invalid-status@example.com",
                Timestamp.from(NOW), Timestamp.from(NOW)));
    }

    @Test
    @DisplayName("users.status에 더 이상 LOCKED를 저장할 수 없다")
    void user_lockedStatus_violatesCheckConstraint() {
        assertThrows(DataIntegrityViolationException.class, () -> jdbcTemplate.update(
                """
                INSERT INTO users (public_id, name, status, email, created_at, updated_at)
                VALUES (?, ?, 'LOCKED', ?, ?, ?)
                """,
                UUID.randomUUID(), "locked-status-user", "locked-status@example.com",
                Timestamp.from(NOW), Timestamp.from(NOW)));
    }

    @Test
    @DisplayName("users.status에 SUSPENDED를 저장할 수 있다")
    void user_suspendedStatus_succeeds() {
        long userId = userRepository.insert(
                UUID.randomUUID(), "suspended-user", UserStatus.SUSPENDED, "suspended@example.com", NOW);

        User user = userRepository.findById(userId).orElseThrow();
        assertEquals(UserStatus.SUSPENDED, user.status());
    }

    @Test
    @DisplayName("정의되지 않은 oauth_account.provider 값은 저장할 수 없다")
    void oauthAccount_invalidProvider_violatesCheckConstraint() {
        long userId = insertUser("user13@example.com");

        assertThrows(DataIntegrityViolationException.class, () -> jdbcTemplate.update(
                """
                INSERT INTO oauth_account
                    (user_id, provider, provider_subject, created_at, updated_at)
                VALUES (?, 'kakao', 'subject-x', ?, ?)
                """,
                userId, Timestamp.from(NOW), Timestamp.from(NOW)));
    }

    @Test
    @DisplayName("failed_login_count는 음수로 저장할 수 없다")
    void localAccount_negativeFailedLoginCount_violatesCheckConstraint() {
        long userId = insertUser("user14@example.com");

        assertThrows(DataIntegrityViolationException.class, () -> jdbcTemplate.update(
                """
                INSERT INTO local_account
                    (user_id, login_id, password_hash, failed_login_count, created_at, updated_at)
                VALUES (?, 'negative-count', 'hash', -1, ?, ?)
                """,
                userId, Timestamp.from(NOW), Timestamp.from(NOW)));
    }

    @Test
    @DisplayName("만료시각은 생성시각보다 이후여야 한다")
    void refreshToken_expiresAtNotAfterCreatedAt_violatesCheckConstraint() {
        long userId = insertUser("user15@example.com");

        assertThrows(DataIntegrityViolationException.class,
                () -> refreshTokenRepository.insert(
                        userId, tokenHash("same-instant"), "device-1", "Device 1", NOW, NOW));
    }

    @Test
    @DisplayName("refresh_token은 device_id 없이 저장할 수 없다")
    void refreshToken_missingDeviceId_violatesNotNullConstraint() {
        long userId = insertUser("user16@example.com");

        assertThrows(DataIntegrityViolationException.class, () -> jdbcTemplate.update(
                """
                INSERT INTO refresh_token
                    (user_id, token_hash, device_name, issued_at, expires_at, created_at)
                VALUES (?, ?, 'Device 1', ?, ?, ?)
                """,
                userId, tokenHash("no-device-id"), Timestamp.from(NOW),
                Timestamp.from(NOW.plusSeconds(60)), Timestamp.from(NOW)));
    }

    @Test
    @DisplayName("token_hash는 64자를 초과하면 저장할 수 없다")
    void refreshToken_tokenHashTooLong_violatesLengthConstraint() {
        long userId = insertUser("user17@example.com");
        String tooLong = "a".repeat(65);

        assertThrows(DataIntegrityViolationException.class,
                () -> refreshTokenRepository.insert(
                        userId, tooLong, "device-1", "Device 1", NOW.plusSeconds(60), NOW));
    }

    @Test
    @DisplayName("존재하지 않는 user_id는 외래키 제약을 위반한다")
    void localAccount_nonExistentUserId_violatesForeignKeyConstraint() {
        long nonExistentUserId = 999_999L;

        assertThrows(DataIntegrityViolationException.class,
                () -> localAccountRepository.insert(nonExistentUserId, "orphan-login", "hash", NOW));
    }

    @Test
    @DisplayName("local_account에는 더 이상 password_changed_at·last_login_at 컬럼이 없다")
    void localAccount_columns_noLongerIncludePasswordChangedAtOrLastLoginAt() {
        Integer count = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM information_schema.columns
                WHERE table_name = 'local_account'
                    AND column_name IN ('password_changed_at', 'last_login_at')
                """,
                Integer.class);
        assertEquals(0, count);
    }

    private long insertUser(String email) {
        return userRepository.insert(UUID.randomUUID(), "test-user", UserStatus.ACTIVE, email, NOW);
    }

    private static String tokenHash(String seed) {
        return (seed + "0".repeat(64)).substring(0, 64);
    }
}
