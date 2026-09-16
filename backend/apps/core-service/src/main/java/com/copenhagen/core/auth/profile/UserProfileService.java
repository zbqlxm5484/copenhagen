package com.copenhagen.core.auth.profile;

import java.util.UUID;

import org.springframework.stereotype.Service;

import com.copenhagen.core.auth.localaccount.LocalAccount;
import com.copenhagen.core.auth.localaccount.LocalAccountRepository;
import com.copenhagen.core.auth.user.User;
import com.copenhagen.core.auth.user.UserRepository;
import com.copenhagen.core.common.error.CoreErrorCode;
import com.copenhagen.core.common.error.CoreException;

@Service
public class UserProfileService {

    private final UserRepository userRepository;
    private final LocalAccountRepository localAccountRepository;

    public UserProfileService(UserRepository userRepository, LocalAccountRepository localAccountRepository) {
        this.userRepository = userRepository;
        this.localAccountRepository = localAccountRepository;
    }

    public UserProfileResult getProfile(UUID userPublicId) {
        User user = userRepository.findByPublicId(userPublicId)
                .orElseThrow(() -> new CoreException(CoreErrorCode.AUTH_TOKEN_INVALID));
        LocalAccount localAccount = localAccountRepository.findByUserId(user.id())
                .orElseThrow(() -> new CoreException(CoreErrorCode.AUTH_TOKEN_INVALID));
        return new UserProfileResult(user.publicId(), localAccount.loginId(), user.name());
    }
}
