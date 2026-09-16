package com.copenhagen.core.auth.profile;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.copenhagen.core.auth.localaccount.LocalAccount;
import com.copenhagen.core.auth.localaccount.LocalAccountRepository;
import com.copenhagen.core.auth.user.User;
import com.copenhagen.core.auth.user.UserRepository;
import com.copenhagen.core.auth.user.UserStatus;
import com.copenhagen.core.common.crypto.EncryptedPii;
import com.copenhagen.core.common.error.CoreErrorCode;
import com.copenhagen.core.common.error.CoreException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class UserProfileServiceTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    private static final long USER_ID = 42L;
    private static final UUID PUBLIC_ID = UUID.randomUUID();

    private UserRepository userRepository;
    private LocalAccountRepository localAccountRepository;
    private UserProfileService service;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        localAccountRepository = mock(LocalAccountRepository.class);
        service = new UserProfileService(userRepository, localAccountRepository);
    }

    @Test
    @DisplayName("정상 조회는 userId/loginId/name 3필드를 반환한다")
    void getProfile_existingUser_returnsSummary() {
        when(userRepository.findByPublicId(PUBLIC_ID)).thenReturn(Optional.of(user()));
        when(localAccountRepository.findByUserId(USER_ID)).thenReturn(Optional.of(localAccount()));

        UserProfileResult result = service.getProfile(PUBLIC_ID);

        assertEquals(PUBLIC_ID, result.userId());
        assertEquals("login-id", result.loginId());
        assertEquals("홍길동", result.name());
    }

    @Test
    @DisplayName("user가 없으면 AUTH_TOKEN_INVALID를 던진다")
    void getProfile_missingUser_throwsInvalid() {
        when(userRepository.findByPublicId(PUBLIC_ID)).thenReturn(Optional.empty());

        CoreException exception = assertThrows(CoreException.class, () -> service.getProfile(PUBLIC_ID));

        assertEquals(CoreErrorCode.AUTH_TOKEN_INVALID, exception.errorCode());
    }

    @Test
    @DisplayName("local_account가 없으면 AUTH_TOKEN_INVALID를 던진다")
    void getProfile_missingLocalAccount_throwsInvalid() {
        when(userRepository.findByPublicId(PUBLIC_ID)).thenReturn(Optional.of(user()));
        when(localAccountRepository.findByUserId(USER_ID)).thenReturn(Optional.empty());

        CoreException exception = assertThrows(CoreException.class, () -> service.getProfile(PUBLIC_ID));

        assertEquals(CoreErrorCode.AUTH_TOKEN_INVALID, exception.errorCode());
    }

    private static User user() {
        return new User(
                USER_ID, PUBLIC_ID, "홍길동", UserStatus.ACTIVE,
                EncryptedPii.of(new byte[] {1}), "email-hash", true,
                null, null, null, null, null, NOW, NOW);
    }

    private static LocalAccount localAccount() {
        return new LocalAccount(
                7L, USER_ID, "login-id", "password-hash", 0, null, null, NOW, NOW);
    }
}
