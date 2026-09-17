package com.copenhagen.pay.payment.authorization;

import com.copenhagen.pay.common.error.PayErrorCode;
import com.copenhagen.pay.common.error.PayException;
import com.copenhagen.pay.common.idempotency.IdempotencyGuard;
import com.copenhagen.pay.common.observability.PayLogContext;
import com.copenhagen.pay.common.validation.RequestValidation;
import com.copenhagen.pay.payment.merchant.MerchantAccount;
import com.copenhagen.pay.payment.merchant.MerchantAccountRepository;
import com.copenhagen.pay.payment.notification.NotificationEventPublisher;
import com.copenhagen.pay.payment.paymentmethod.PaymentMethod;
import com.copenhagen.pay.payment.paymentmethod.PaymentMethodRepository;
import com.copenhagen.pay.payment.policy.AuthorizationSessionStatus;
import java.time.Clock;
import java.time.Instant;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Service
public class AuthorizationSessionService {

    private static final String KRW = "KRW";
    private static final String BIOMETRIC = "BIOMETRIC";
    private static final int MAX_SUMMARY_ITEMS = 100;
    private static final int MAX_ITEM_NAME_LENGTH = 200;
    private static final int MAX_OPTION_LENGTH = 200;
    private static final Logger log = LoggerFactory.getLogger(AuthorizationSessionService.class);

    private final AuthorizationSessionStore store;
    private final MerchantAccountRepository merchantAccountRepository;
    private final PaymentMethodRepository paymentMethodRepository;
    private final IdempotencyGuard idempotencyGuard;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final ApprovalProperties approvalProperties;
    private final MerchantCheckoutVerifier checkoutVerifier;
    private final VerifiedCheckoutEvidenceRepository evidenceRepository;
    private NotificationEventPublisher notificationEventPublisher = NotificationEventPublisher.noop();

    public AuthorizationSessionService(
            AuthorizationSessionStore store,
            MerchantAccountRepository merchantAccountRepository,
            PaymentMethodRepository paymentMethodRepository,
            IdempotencyGuard idempotencyGuard,
            ObjectMapper objectMapper,
            Clock clock,
            ApprovalProperties approvalProperties,
            MerchantCheckoutVerifier checkoutVerifier,
            VerifiedCheckoutEvidenceRepository evidenceRepository) {
        this.store = store;
        this.merchantAccountRepository = merchantAccountRepository;
        this.paymentMethodRepository = paymentMethodRepository;
        this.idempotencyGuard = idempotencyGuard;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.approvalProperties = approvalProperties;
        this.checkoutVerifier = checkoutVerifier;
        this.evidenceRepository = evidenceRepository;
    }

    @Autowired(required = false)
    public void setNotificationEventPublisher(NotificationEventPublisher notificationEventPublisher) {
        if (notificationEventPublisher != null) {
            this.notificationEventPublisher = notificationEventPublisher;
        }
    }

    @Transactional
    public CreateResult create(
            CreateAuthorizationSessionCommand command,
            String idempotencyKey,
            String requestHash) {
        validate(command, idempotencyKey, requestHash);
        PayLogContext.putFingerprint(PayLogContext.USER_ID, command.userId());
        PayLogContext.put(PayLogContext.ORDER_ID, command.purchaseRequestId());
        log.info(
                "pay.approval.create.start merchantId={} checkoutId={} amountMinor={} currency={}",
                command.merchantId(),
                command.checkoutId(),
                command.amountMinor(),
                command.currency());

        MerchantAccount merchantAccount = merchantAccountRepository
                .findActiveByMerchantId(command.merchantId())
                .orElseThrow(() -> new PayException(PayErrorCode.RESOURCE_NOT_FOUND));

        Optional<AuthorizationSession> existing = store.findByMerchantAndIdempotencyKey(
                merchantAccount.id(),
                idempotencyKey);
        if (existing.isPresent()) {
            IdempotencyGuard.Decision decision = idempotencyGuard.check(
                    existing.get().requestHash(),
                    requestHash,
                    existing.get().status().name());
            if (decision == IdempotencyGuard.Decision.REPLAY) {
                PayLogContext.put(PayLogContext.APPROVAL_ID, existing.get().id());
                log.info(
                        "pay.approval.create.replay approvalId={} status={}",
                        existing.get().id(),
                        existing.get().status());
                return new CreateResult(existing.get(), true);
            }
        }

        store.findActiveByMerchantAndPurchaseRequest(
                        merchantAccount.id(),
                        command.purchaseRequestId())
                .ifPresent(session -> {
                    throw stateConflict(session.status());
                });

        MerchantCheckoutVerifier.VerificationResult verification = checkoutVerifier.verify(command);
        String displaySnapshotJson = serializeDisplaySnapshot(command.displaySnapshot());
        PaymentMethod selectedPaymentMethod = selectPaymentMethod(command.userId());
        AuthorizationSessionStatus status = selectedPaymentMethod == null
                ? AuthorizationSessionStatus.PAYMENT_METHOD_REQUIRED
                : AuthorizationSessionStatus.PENDING;
        Instant now = clock.instant();
        String checkoutDigest = CheckoutHashCodec.toInternalDigest(command.checkoutHash());
        AuthorizationSession session = new AuthorizationSession(
                UUID.randomUUID().toString(),
                command.purchaseRequestId(),
                command.userId(),
                merchantAccount.id(),
                merchantAccount.merchantId(),
                merchantAccount.displayName(),
                command.checkoutId(),
                checkoutDigest,
                displaySnapshotJson,
                command.amountMinor(),
                command.currency(),
                selectedPaymentMethod == null ? null : selectedPaymentMethod.id(),
                status,
                idempotencyKey,
                requestHash,
                BIOMETRIC,
                now.plus(Duration.ofSeconds(approvalProperties.expirationSeconds())),
                null,
                null,
                now,
                now,
                0);
        PayLogContext.put(PayLogContext.APPROVAL_ID, session.id());

        if (store.insert(session)) {
            VerifiedCheckoutEvidence evidence = VerifiedCheckoutEvidence.from(
                    UUID.randomUUID().toString(),
                    session.id(),
                    command,
                    checkoutDigest,
                    verification,
                    now);
            if (!evidenceRepository.insert(evidence)) {
                throw new PayException(PayErrorCode.INTERNAL_ERROR);
            }
            if (status == AuthorizationSessionStatus.PENDING) {
                notificationEventPublisher.publishApprovalRequested(session);
            }
            log.info(
                    "pay.approval.create.complete approvalId={} status={}",
                    session.id(),
                    session.status());
            return new CreateResult(session, false);
        }

        CreateResult concurrentResult = resolveConcurrentInsert(
                merchantAccount.id(),
                command.purchaseRequestId(),
                idempotencyKey,
                requestHash);
        PayLogContext.put(PayLogContext.APPROVAL_ID, concurrentResult.session().id());
        log.info(
                "pay.approval.create.concurrent_result approvalId={} replay={}",
                concurrentResult.session().id(),
                concurrentResult.replay());
        return concurrentResult;
    }

    @Transactional
    public List<AuthorizationSession> findForUser(
            String userId,
            AuthorizationSessionStatus status) {
        RequestValidation.requireLengthAtMost(userId, "userId", 36);
        PayLogContext.putFingerprint(PayLogContext.USER_ID, userId);
        log.debug("pay.approval.list.start status={}", status);
        Instant now = clock.instant();
        store.expireOverdueForUser(userId, now);
        List<AuthorizationSession> sessions = store.findByUserId(userId, status);
        log.debug("pay.approval.list.complete count={}", sessions.size());
        return sessions;
    }

    @Transactional
    public AuthorizationSession findDetail(String userId, String authorizationSessionId) {
        RequestValidation.requireLengthAtMost(userId, "userId", 36);
        RequestValidation.requireLengthAtMost(
                authorizationSessionId,
                "approvalId",
                36);
        PayLogContext.putFingerprint(PayLogContext.USER_ID, userId);
        PayLogContext.put(PayLogContext.APPROVAL_ID, authorizationSessionId);
        log.debug("pay.approval.detail.start");
        Instant now = clock.instant();
        store.expireOverdueByIdAndUser(authorizationSessionId, userId, now);
        AuthorizationSession session = store.findByIdAndUserId(authorizationSessionId, userId)
                .orElseThrow(() -> new PayException(PayErrorCode.RESOURCE_NOT_FOUND));
        PayLogContext.put(PayLogContext.ORDER_ID, session.purchaseRequestId());
        log.debug("pay.approval.detail.complete status={}", session.status());
        return session;
    }

    @Transactional
    public AuthorizationSession selectPaymentMethod(
            String userId,
            String authorizationSessionId,
            String paymentMethodId) {
        RequestValidation.requireLengthAtMost(userId, "userId", 36);
        RequestValidation.requireLengthAtMost(
                authorizationSessionId,
                "approvalId",
                36);
        RequestValidation.requireLengthAtMost(
                paymentMethodId,
                "paymentMethodId",
                36);
        PayLogContext.putFingerprint(PayLogContext.USER_ID, userId);
        PayLogContext.put(PayLogContext.APPROVAL_ID, authorizationSessionId);
        log.info("pay.approval.payment_method.select.start");

        Instant now = clock.instant();
        store.expireOverdueByIdAndUser(authorizationSessionId, userId, now);
        AuthorizationSession session = store.findByIdAndUserId(
                        authorizationSessionId,
                        userId)
                .orElseThrow(() -> new PayException(PayErrorCode.RESOURCE_NOT_FOUND));
        ensurePaymentMethodSelectionAllowed(session, now);

        paymentMethodRepository.findActiveByIdAndUserIdForUpdate(paymentMethodId, userId)
                .orElseThrow(() -> new PayException(PayErrorCode.PAYMENT_METHOD_NOT_FOUND));

        if (!store.selectPaymentMethod(
                authorizationSessionId,
                userId,
                paymentMethodId,
                now)) {
            AuthorizationSession current = store.findByIdAndUserId(
                            authorizationSessionId,
                            userId)
                    .orElseThrow(() -> new PayException(PayErrorCode.RESOURCE_NOT_FOUND));
            throw stateConflict(current.status());
        }

        AuthorizationSession updated = store.findByIdAndUserId(authorizationSessionId, userId)
                .orElseThrow(() -> new PayException(PayErrorCode.RESOURCE_NOT_FOUND));
        if (session.status() == AuthorizationSessionStatus.PAYMENT_METHOD_REQUIRED
                && updated.status() == AuthorizationSessionStatus.PENDING) {
            notificationEventPublisher.publishApprovalRequested(updated);
        }
        log.info(
                "pay.approval.payment_method.select.complete status={}",
                updated.status());
        return updated;
    }

    public AuthorizationDisplaySnapshot displaySnapshot(AuthorizationSession session) {
        try {
            return objectMapper.readValue(
                    session.displaySnapshotJson(),
                    AuthorizationDisplaySnapshot.class);
        } catch (JacksonException exception) {
            throw new PayException(
                    PayErrorCode.INTERNAL_ERROR,
                    PayErrorCode.INTERNAL_ERROR.defaultMessage(),
                    null,
                    PayErrorCode.INTERNAL_ERROR.defaultNextAction(),
                    exception);
        }
    }

    private CreateResult resolveConcurrentInsert(
            String merchantAccountId,
            String purchaseRequestId,
            String idempotencyKey,
            String requestHash) {
        Optional<AuthorizationSession> existing = store.findByMerchantAndIdempotencyKey(
                merchantAccountId,
                idempotencyKey);
        if (existing.isPresent()) {
            IdempotencyGuard.Decision decision = idempotencyGuard.check(
                    existing.get().requestHash(),
                    requestHash,
                    existing.get().status().name());
            if (decision == IdempotencyGuard.Decision.REPLAY) {
                return new CreateResult(existing.get(), true);
            }
        }

        Optional<AuthorizationSession> active = store.findActiveByMerchantAndPurchaseRequest(
                merchantAccountId,
                purchaseRequestId);
        if (active.isPresent()) {
            throw stateConflict(active.get().status());
        }
        throw new PayException(PayErrorCode.INTERNAL_ERROR);
    }

    private PaymentMethod selectPaymentMethod(String userId) {
        List<PaymentMethod> paymentMethods = paymentMethodRepository.findActiveByUserId(userId);
        return paymentMethods.stream()
                .filter(PaymentMethod::defaultMethod)
                .findFirst()
                .orElseGet(() -> paymentMethods.stream().findFirst().orElse(null));
    }

    private String serializeDisplaySnapshot(AuthorizationDisplaySnapshot displaySnapshot) {
        try {
            return objectMapper.writeValueAsString(displaySnapshot);
        } catch (JacksonException exception) {
            throw new PayException(
                    PayErrorCode.INVALID_REQUEST,
                    "표시용 상품 요약을 저장할 수 없습니다.",
                    null,
                    "CHECK_DISPLAY_SNAPSHOT",
                    exception);
        }
    }

    private void validate(
            CreateAuthorizationSessionCommand command,
            String idempotencyKey,
            String requestHash) {
        if (command == null) {
            throw new PayException(PayErrorCode.INVALID_REQUEST);
        }
        RequestValidation.requireLengthAtMost(command.purchaseRequestId(), "purchaseRequestId", 36);
        RequestValidation.requireLengthAtMost(command.userId(), "userId", 36);
        RequestValidation.requireLengthAtMost(command.merchantId(), "merchantId", 100);
        RequestValidation.requireLengthAtMost(command.checkoutId(), "checkoutId", 150);
        RequestValidation.requireLengthAtMost(command.checkoutHash(), "checkoutHash", 100);
        RequestValidation.requireLengthAtMost(idempotencyKey, "idempotencyKey", 100);
        RequestValidation.requireLengthAtMost(requestHash, "requestHash", 64);
        if (!isCompactJws(command.checkoutJwt())) {
            throw new PayException(
                    PayErrorCode.INVALID_REQUEST,
                    "checkoutJwt는 Compact JWS 형식이어야 합니다.",
                    null,
                    "CHECK_CHECKOUT_JWT");
        }
        CheckoutHashCodec.toInternalDigest(command.checkoutHash());
        CheckoutHashCodec.validateMatchesJwt(command.checkoutHash(), command.checkoutJwt());
        if (command.amountMinor() < 0) {
            throw new PayException(PayErrorCode.INVALID_REQUEST);
        }
        if (!KRW.equals(command.currency())) {
            throw new PayException(
                    PayErrorCode.INVALID_REQUEST,
                    "MVP에서는 KRW 통화만 사용할 수 있습니다.",
                    null,
                    "USE_KRW_CURRENCY");
        }
        validateDisplaySnapshot(command.displaySnapshot());
    }

    private boolean isCompactJws(String checkoutJwt) {
        if (checkoutJwt == null || checkoutJwt.isBlank()) {
            return false;
        }
        String[] segments = checkoutJwt.split("\\.", -1);
        if (segments.length != 3
                || java.util.Arrays.stream(segments)
                .anyMatch(segment -> segment.isBlank() || !segment.matches("[A-Za-z0-9_-]+"))) {
            return false;
        }
        try {
            for (String segment : segments) {
                java.util.Base64.getUrlDecoder().decode(segment);
            }
            return true;
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private void validateDisplaySnapshot(AuthorizationDisplaySnapshot displaySnapshot) {
        if (displaySnapshot == null
                || displaySnapshot.merchantName() == null
                || displaySnapshot.merchantName().isBlank()
                || displaySnapshot.merchantName().length() > 200
                || displaySnapshot.summary().isEmpty()
                || displaySnapshot.summary().size() > MAX_SUMMARY_ITEMS) {
            throw new PayException(
                    PayErrorCode.INVALID_REQUEST,
                    "display 요약 형식이 올바르지 않습니다.",
                    null,
                    "CHECK_DISPLAY_SNAPSHOT");
        }
        displaySnapshot.summary().forEach(this::validateSummaryItem);
    }

    private void validateSummaryItem(AuthorizationDisplaySnapshot.SummaryItem item) {
        if (item == null
                || item.name() == null
                || item.name().isBlank()
                || item.name().length() > MAX_ITEM_NAME_LENGTH
                || item.option() != null && item.option().length() > MAX_OPTION_LENGTH
                || item.quantity() <= 0
                || item.amountMinor() < 0) {
            throw new PayException(
                    PayErrorCode.INVALID_REQUEST,
                    "display.summary 항목 형식이 올바르지 않습니다.",
                    null,
                    "CHECK_DISPLAY_SNAPSHOT");
        }
    }

    private void ensurePaymentMethodSelectionAllowed(
            AuthorizationSession session,
            Instant now) {
        if (session.status() != AuthorizationSessionStatus.PAYMENT_METHOD_REQUIRED
                && session.status() != AuthorizationSessionStatus.PENDING) {
            throw stateConflict(session.status());
        }
        if (!now.isBefore(session.expiresAt())) {
            throw stateConflict(session.status());
        }
    }

    private PayException stateConflict(AuthorizationSessionStatus currentStatus) {
        return new PayException(
                PayErrorCode.STATE_CONFLICT,
                PayErrorCode.STATE_CONFLICT.defaultMessage(),
                currentStatus.name(),
                PayErrorCode.STATE_CONFLICT.defaultNextAction());
    }

    public record CreateResult(
            AuthorizationSession session,
            boolean replay) {
    }
}
