package com.copenhagen.core.auth.profile;

import java.util.UUID;

public record UserProfileResult(UUID userId, String loginId, String name) {
}
