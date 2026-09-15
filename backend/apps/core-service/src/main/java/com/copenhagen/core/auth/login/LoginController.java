package com.copenhagen.core.auth.login;

import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/core/v1/auth/login")
public class LoginController {

    private final AuthLoginService authLoginService;

    public LoginController(AuthLoginService authLoginService) {
        this.authLoginService = authLoginService;
    }

    @PostMapping
    public ResponseEntity<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
        AuthLoginOutcome outcome = authLoginService.login(
                request.loginId(), request.password(), request.deviceId(), request.deviceName());
        return ResponseEntity.ok(LoginResponse.from(outcome));
    }

    public record LoginRequest(
            @NotBlank
            String loginId,

            @NotBlank
            String password,

            @NotBlank
            String deviceId,

            @NotBlank
            String deviceName) {

        @Override
        public String toString() {
            return "LoginRequest[loginId='" + loginId + "', password=<redacted>, deviceId='" + deviceId
                    + "', deviceName='" + deviceName + "']";
        }
    }

    public record LoginResponse(
            String accessToken,
            String refreshToken,
            long expiresIn,
            UserSummary user) {

        static LoginResponse from(AuthLoginOutcome outcome) {
            return new LoginResponse(
                    outcome.accessToken(),
                    outcome.refreshToken(),
                    outcome.expiresInSeconds(),
                    UserSummary.from(outcome.loginResult()));
        }

        @Override
        public String toString() {
            return "LoginResponse[accessToken=<redacted>, refreshToken=<redacted>, expiresIn=" + expiresIn
                    + ", user=" + user + ']';
        }

        public record UserSummary(UUID userId, String loginId, String name) {

            static UserSummary from(LoginResult result) {
                return new UserSummary(result.userId(), result.loginId(), result.name());
            }
        }
    }
}
