package com.copenhagen.core.auth.profile;

import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.copenhagen.core.common.auth.CoreRequestContext;

@RestController
@RequestMapping("/api/core/v1/users/me")
public class UserProfileController {

    private final UserProfileService userProfileService;

    public UserProfileController(UserProfileService userProfileService) {
        this.userProfileService = userProfileService;
    }

    @GetMapping
    public UserProfileResponse me(HttpServletRequest request) {
        UUID principal = CoreRequestContext.requirePrincipal(request);
        return UserProfileResponse.from(userProfileService.getProfile(principal));
    }

    public record UserProfileResponse(UUID userId, String loginId, String name) {

        static UserProfileResponse from(UserProfileResult result) {
            return new UserProfileResponse(result.userId(), result.loginId(), result.name());
        }
    }
}
