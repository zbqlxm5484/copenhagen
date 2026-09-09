package com.copenhagen.pay.payment.authorization;

import com.copenhagen.pay.common.auth.PayPrincipal;
import com.copenhagen.pay.common.error.PayErrorCode;
import com.copenhagen.pay.common.error.PayException;
import com.copenhagen.pay.common.idempotency.IdempotencyKey;
import com.copenhagen.pay.common.idempotency.RequestHashGenerator;
import com.copenhagen.pay.common.web.PayRequestContext;
import com.copenhagen.pay.payment.policy.AuthorizationSessionStatus;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

@RestController
@RequestMapping
public class AuthorizationSessionController {

    private final AuthorizationSessionService service;
    private final RequestHashGenerator requestHashGenerator;

    public AuthorizationSessionController(
            AuthorizationSessionService service,
            RequestHashGenerator requestHashGenerator) {
        this.service = service;
        this.requestHashGenerator = requestHashGenerator;
    }

    @PostMapping("/internal/v1/payment-approvals")
    public ResponseEntity<ApprovalCreatedResponse> create(
            @Valid @RequestBody CreateAuthorizationSessionRequest request,
            HttpServletRequest httpRequest) {
        IdempotencyKey idempotencyKey = PayRequestContext.findIdempotencyKey(httpRequest)
                .orElseThrow(() -> new PayException(PayErrorCode.IDEMPOTENCY_KEY_REQUIRED));
        String requestHash = requestHashGenerator.generate(httpRequest, request);
        AuthorizationSessionService.CreateResult result = service.create(
                request.toCommand(),
                idempotencyKey.value(),
                requestHash);
        ApprovalCreatedResponse response = ApprovalCreatedResponse.from(result.session());
        return result.replay()
                ? ResponseEntity.ok(response)
                : ResponseEntity.status(201).body(response);
    }

    @GetMapping("/api/v1/payment-approvals")
    public ResponseEntity<ApprovalListResponse> findForUser(
            @RequestParam(required = false) String status,
            HttpServletRequest httpRequest) {
        String userId = userId(httpRequest);
        AuthorizationSessionStatus requestedStatus = parseStatus(status);
        List<ApprovalListItemResponse> items = service.findForUser(userId, requestedStatus).stream()
                .map(session -> ApprovalListItemResponse.from(
                        session,
                        service.displaySnapshot(session)))
                .toList();
        return ResponseEntity.ok(new ApprovalListResponse(items));
    }

    @GetMapping("/api/v1/payment-approvals/{approvalId}")
    public ResponseEntity<ApprovalDetailResponse> findDetail(
            @PathVariable String approvalId,
            HttpServletRequest httpRequest) {
        AuthorizationSession session = service.findDetail(userId(httpRequest), approvalId);
        return ResponseEntity.ok(ApprovalDetailResponse.from(
                session,
                service.displaySnapshot(session)));
    }

    private String userId(HttpServletRequest request) {
        PayPrincipal principal = PayRequestContext.requirePrincipal(request);
        return principal.subject();
    }

    private AuthorizationSessionStatus parseStatus(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        try {
            return AuthorizationSessionStatus.valueOf(status);
        } catch (IllegalArgumentException exception) {
            throw new PayException(
                    PayErrorCode.INVALID_REQUEST,
                    "승인 상태 값이 올바르지 않습니다.",
                    null,
                    "CHECK_STATUS",
                    exception);
        }
    }

    public record CreateAuthorizationSessionRequest(
            @NotBlank @Size(max = 36) String purchaseRequestId,
            @NotBlank @Size(max = 36) String userId,
            @NotBlank @Size(max = 100) String merchantId,
            @NotBlank @Size(max = 150) String checkoutId,
            @NotBlank @Pattern(regexp = "[0-9a-fA-F]{64}") String checkoutDigest,
            @NotNull JsonNode checkoutArtifact,
            @NotNull @Valid DisplaySnapshotRequest display,
            @PositiveOrZero long amountMinor,
            @NotBlank @Pattern(regexp = "KRW") String currency) {

        private CreateAuthorizationSessionCommand toCommand() {
            return new CreateAuthorizationSessionCommand(
                    purchaseRequestId,
                    userId,
                    merchantId,
                    checkoutId,
                    checkoutDigest,
                    checkoutArtifact,
                    display.toDomain(),
                    amountMinor,
                    currency);
        }

        @Override
        public String toString() {
            return "CreateAuthorizationSessionRequest["
                    + "purchaseRequestId='" + purchaseRequestId + '\''
                    + ", userId='" + userId + '\''
                    + ", merchantId='" + merchantId + '\''
                    + ", checkoutId='" + checkoutId + '\''
                    + ", checkoutDigest=<redacted>"
                    + ", checkoutArtifact=<redacted>"
                    + ", display=<redacted>"
                    + ", amountMinor=" + amountMinor
                    + ", currency='" + currency + '\''
                    + ']';
        }
    }

    public record DisplaySnapshotRequest(
            @NotBlank @Size(max = 200) String merchantName,
            @NotEmpty @Size(max = 100) List<@Valid SummaryItemRequest> summary) {

        public DisplaySnapshotRequest {
            summary = summary == null ? List.of() : List.copyOf(summary);
        }

        private AuthorizationDisplaySnapshot toDomain() {
            return new AuthorizationDisplaySnapshot(
                    merchantName,
                    summary.stream()
                            .map(SummaryItemRequest::toDomain)
                            .toList());
        }
    }

    public record SummaryItemRequest(
            @NotBlank @Size(max = 200) String name,
            @Size(max = 200) String option,
            @Min(1) int quantity,
            @PositiveOrZero long amountMinor) {

        private AuthorizationDisplaySnapshot.SummaryItem toDomain() {
            return new AuthorizationDisplaySnapshot.SummaryItem(
                    name,
                    option,
                    quantity,
                    amountMinor);
        }
    }

    public record ApprovalCreatedResponse(
            String approvalId,
            String status,
            String requiredAction,
            Instant expiresAt,
            String nextAction) {

        private static ApprovalCreatedResponse from(AuthorizationSession session) {
            String nextAction = session.status()
                    == AuthorizationSessionStatus.PAYMENT_METHOD_REQUIRED
                    ? "REGISTER_OR_SELECT_PAYMENT_METHOD"
                    : "APPROVE_IN_APP";
            return new ApprovalCreatedResponse(
                    session.id(),
                    session.status().name(),
                    session.requiredAction(),
                    session.expiresAt(),
                    nextAction);
        }
    }

    public record ApprovalListResponse(List<ApprovalListItemResponse> items) {

        public ApprovalListResponse {
            items = List.copyOf(items);
        }
    }

    public record ApprovalListItemResponse(
            String approvalId,
            String merchantName,
            String checkoutId,
            List<SummaryItemResponse> summary,
            long amountMinor,
            String currency,
            String status,
            String requiredAction,
            Instant expiresAt) {

        private static ApprovalListItemResponse from(
                AuthorizationSession session,
                AuthorizationDisplaySnapshot displaySnapshot) {
            return new ApprovalListItemResponse(
                    session.id(),
                    displaySnapshot.merchantName(),
                    session.externalCheckoutId(),
                    SummaryItemResponse.from(displaySnapshot),
                    session.amountMinor(),
                    session.currency(),
                    session.status().name(),
                    session.requiredAction(),
                    session.expiresAt());
        }
    }

    public record ApprovalDetailResponse(
            String approvalId,
            String merchantName,
            String checkoutId,
            String checkoutDigest,
            List<SummaryItemResponse> summary,
            long amountMinor,
            String currency,
            String selectedPaymentMethodId,
            String status,
            String requiredAction,
            Instant expiresAt,
            BiometricChallengeResponse biometricChallenge) {

        private static ApprovalDetailResponse from(
                AuthorizationSession session,
                AuthorizationDisplaySnapshot displaySnapshot) {
            return new ApprovalDetailResponse(
                    session.id(),
                    displaySnapshot.merchantName(),
                    session.externalCheckoutId(),
                    session.checkoutDigest(),
                    SummaryItemResponse.from(displaySnapshot),
                    session.amountMinor(),
                    session.currency(),
                    session.selectedPaymentMethodId(),
                    session.status().name(),
                    session.requiredAction(),
                    session.expiresAt(),
                    null);
        }
    }

    public record SummaryItemResponse(
            String name,
            String option,
            int quantity,
            long amountMinor) {

        private static List<SummaryItemResponse> from(
                AuthorizationDisplaySnapshot displaySnapshot) {
            return displaySnapshot.summary().stream()
                    .map(item -> new SummaryItemResponse(
                            item.name(),
                            item.option(),
                            item.quantity(),
                            item.amountMinor()))
                    .toList();
        }
    }

    public record BiometricChallengeResponse(
            String challengeId,
            String challenge,
            Instant expiresAt) {
    }
}
