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
        long userId = insertUser();
        UUID publicId = userRepository.findById(userId).orElseThrow().publicId();

        long localAccountId = localAccountRepository.insert(userId, "user1", "hashed-password", NOW);
        long oauthAccountId = oauthAccountRepository.insert(
                userId, OauthProvider.GOOGLE, "google-subject-1", "user1@example.com", true, NOW);
        long refreshTokenId = refreshTokenRepository.insert(userId, "token-hash-1", NOW.plusSeconds(3600), NOW);

        User user = userRepository.findByPublicId(publicId).orElseThrow();
        assertEquals(userId, user.id());
        assertEquals(UserStatus.ACTIVE, user.status());

        LocalAccount localAccount = localAccountRepository.findByLoginId("user1").orElseThrow();
        assertEquals(localAccountId, localAccount.id());
        assertEquals(userId, localAccount.userId());
        assertEquals("hashed-password", localAccount.passwordHash());
        assertEquals(0, localAccount.failedLoginAttempts());
        assertNull(localAccount.lastFailedLoginAt());
        assertNull(localAccount.lockedUntil());
        assertNull(localAccount.lastLoginAt());

        OauthAccount oauthAccount = oauthAccountRepository
                .findByProviderAndProviderSubject(OauthProvider.GOOGLE, "google-subject-1")
                .orElseThrow();
        assertEquals(oauthAccountId, oauthAccount.id());
        assertEquals(userId, oauthAccount.userId());
        assertTrue(oauthAccount.emailVerified());

        RefreshToken refreshToken = refreshTokenRepository.findByTokenHash("token-hash-1").orElseThrow();
        assertEquals(refreshTokenId, refreshToken.id());
        assertEquals(userId, refreshToken.userId());
    }

    @Test
    @DisplayName("사용자 1명에 자체 로그인 계정은 하나만 연결할 수 있다")
    void localAccount_secondForSameUser_violatesUniqueConstraint() {
        long userId = insertUser();
        localAccountRepository.insert(userId, "first-login", "hash-1", NOW);

        assertThrows(DuplicateKeyException.class,
                () -> localAccountRepository.insert(userId, "second-login", "hash-2", NOW));
    }

    @Test
    @DisplayName("사용자 1명은 서로 다른 OAuth 계정을 여러 개 연결할 수 있다")
    void oauthAccount_multipleUsers_eachSucceeds() {
        long firstUserId = insertUser();
        long secondUserId = insertUser();

        assertTrue(oauthAccountRepository
                .insert(firstUserId, OauthProvider.GOOGLE, "subject-a", null, false, NOW) > 0);
        assertTrue(oauthAccountRepository
                .insert(secondUserId, OauthProvider.GOOGLE, "subject-b", null, false, NOW) > 0);
    }

    @Test
    @DisplayName("사용자 1명에 리프레시 토큰을 여러 개 저장할 수 있다")
    void refreshToken_multipleForSameUser_bothSucceed() {
        long userId = insertUser();

        refreshTokenRepository.insert(userId, "token-a", NOW.plusSeconds(60), NOW);
        refreshTokenRepository.insert(userId, "token-b", NOW.plusSeconds(120), NOW);

        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM refresh_token WHERE user_id = ?", Integer.class, userId);
        assertEquals(2, count);
    }

    @Test
    @DisplayName("login_id는 중복 저장할 수 없다")
    void localAccount_duplicateLoginId_violatesUniqueConstraint() {
        long firstUserId = insertUser();
        long secondUserId = insertUser();
        localAccountRepository.insert(firstUserId, "duplicate-id", "hash-1", NOW);

        assertThrows(DuplicateKeyException.class,
                () -> localAccountRepository.insert(secondUserId, "duplicate-id", "hash-2", NOW));

        assertFalse(localAccountRepository.existsByLoginId("duplicate-id-2"));
    }

    @Test
    @DisplayName("public_id는 중복 저장할 수 없다")
    void user_duplicatePublicId_violatesUniqueConstraint() {
        UUID publicId = UUID.randomUUID();
        userRepository.insert(publicId, "first", UserStatus.ACTIVE, NOW);

        assertThrows(DuplicateKeyException.class,
                () -> userRepository.insert(publicId, "second", UserStatus.ACTIVE, NOW));
    }

    @Test
    @DisplayName("동일 provider와 provider_subject 조합은 중복 저장할 수 없다")
    void oauthAccount_duplicateProviderAndSubject_violatesUniqueConstraint() {
        long firstUserId = insertUser();
        long secondUserId = insertUser();
        oauthAccountRepository.insert(firstUserId, OauthProvider.GOOGLE, "shared-subject", null, false, NOW);

        assertThrows(DuplicateKeyException.class,
                () -> oauthAccountRepository
                        .insert(secondUserId, OauthProvider.GOOGLE, "shared-subject", null, false, NOW));
    }

    @Test
    @DisplayName("한 사용자가 같은 provider로 OAuth 계정을 두 번 연결할 수 없다")
    void oauthAccount_sameUserSameProviderTwice_violatesUniqueConstraint() {
        long userId = insertUser();
        oauthAccountRepository.insert(userId, OauthProvider.GOOGLE, "subject-1", null, false, NOW);

        assertThrows(DuplicateKeyException.class,
                () -> oauthAccountRepository.insert(userId, OauthProvider.GOOGLE, "subject-2", null, false, NOW));
    }

    @Test
    @DisplayName("token_hash는 중복 저장할 수 없다")
    void refreshToken_duplicateTokenHash_violatesUniqueConstraint() {
        long firstUserId = insertUser();
        long secondUserId = insertUser();
        refreshTokenRepository.insert(firstUserId, "shared-token-hash", NOW.plusSeconds(60), NOW);

        assertThrows(DuplicateKeyException.class,
                () -> refreshTokenRepository.insert(secondUserId, "shared-token-hash", NOW.plusSeconds(60), NOW));
    }

    @Test
    @DisplayName("정의되지 않은 users.status 값은 저장할 수 없다")
    void user_invalidStatus_violatesCheckConstraint() {
        assertThrows(DataIntegrityViolationException.class, () -> jdbcTemplate.update(
                """
                INSERT INTO users (public_id, name, status, created_at, updated_at)
                VALUES (?, ?, 'DELETED', ?, ?)
                """,
                UUID.randomUUID(), "invalid-status-user", Timestamp.from(NOW), Timestamp.from(NOW)));
    }

    @Test
    @DisplayName("정의되지 않은 oauth_account.provider 값은 저장할 수 없다")
    void oauthAccount_invalidProvider_violatesCheckConstraint() {
        long userId = insertUser();

        assertThrows(DataIntegrityViolationException.class, () -> jdbcTemplate.update(
                """
                INSERT INTO oauth_account
                    (user_id, provider, provider_subject, email_verified, created_at, updated_at)
                VALUES (?, 'kakao', 'subject-x', FALSE, ?, ?)
                """,
                userId, Timestamp.from(NOW), Timestamp.from(NOW)));
    }

    @Test
    @DisplayName("failed_login_attempts는 음수로 저장할 수 없다")
    void localAccount_negativeFailedLoginAttempts_violatesCheckConstraint() {
        long userId = insertUser();

        assertThrows(DataIntegrityViolationException.class, () -> jdbcTemplate.update(
                """
                INSERT INTO local_account
                    (user_id, login_id, password_hash, password_changed_at,
                     failed_login_attempts, created_at, updated_at)
                VALUES (?, 'negative-attempts', 'hash', ?, -1, ?, ?)
                """,
                userId, Timestamp.from(NOW), Timestamp.from(NOW), Timestamp.from(NOW)));
    }

    @Test
    @DisplayName("만료시각은 생성시각보다 이후여야 한다")
    void refreshToken_expiresAtNotAfterCreatedAt_violatesCheckConstraint() {
        long userId = insertUser();

        assertThrows(DataIntegrityViolationException.class,
                () -> refreshTokenRepository.insert(userId, "same-instant-token", NOW, NOW));
    }

    @Test
    @DisplayName("존재하지 않는 user_id는 외래키 제약을 위반한다")
    void localAccount_nonExistentUserId_violatesForeignKeyConstraint() {
        long nonExistentUserId = 999_999L;

        assertThrows(DataIntegrityViolationException.class,
                () -> localAccountRepository.insert(nonExistentUserId, "orphan-login", "hash", NOW));
    }

    private long insertUser() {
        return userRepository.insert(UUID.randomUUID(), "test-user", UserStatus.ACTIVE, NOW);
    }
}
