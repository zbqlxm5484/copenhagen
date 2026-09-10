package com.copenhagen.pay.payment.credential.biometric;

import com.copenhagen.pay.common.auth.PayPrincipal;
import com.copenhagen.pay.common.web.PayRequestContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/pay/v1/users/me/biometric-credentials")
public class BiometricCredentialController {

    private final BiometricCredentialService service;

    public BiometricCredentialController(BiometricCredentialService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<BiometricCredentialResponse> register(
            @Valid @RequestBody RegisterBiometricCredentialRequest request,
            HttpServletRequest httpRequest) {
        PayPrincipal principal = PayRequestContext.requirePrincipal(httpRequest);
        BiometricCredential credential = service.register(
                principal.subject(),
                request.deviceId(),
                request.publicKey(),
                request.credentialType());
        BiometricCredentialResponse response = new BiometricCredentialResponse(
                credential.id(),
                credential.registeredAt());
        return ResponseEntity.status(201).body(response);
    }

    public record RegisterBiometricCredentialRequest(
            @NotBlank @Size(max = 150) String deviceId,
            @NotBlank @Size(max = 8192) String publicKey,
            @NotBlank @Size(max = 30) String credentialType) {

        @Override
        public String toString() {
            return "RegisterBiometricCredentialRequest["
                    + "deviceId='" + deviceId + '\''
                    + ", publicKey=<redacted>"
                    + ", credentialType='" + credentialType + '\''
                    + ']';
        }
    }

    public record BiometricCredentialResponse(
            String credentialId,
            Instant registeredAt) {
    }
}
