package com.copenhagen.core.auth.oauthaccount;

import java.time.Instant;

public record OauthAccount(
        long id,
        long userId,
        OauthProvider provider,
        String providerSubject,
        String email,
        boolean emailVerified,
        Instant lastLoginAt,
        Instant createdAt,
        Instant updatedAt) {
}
