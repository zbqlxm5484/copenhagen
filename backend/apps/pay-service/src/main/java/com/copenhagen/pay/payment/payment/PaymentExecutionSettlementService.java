package com.copenhagen.pay.payment.payment;

import com.copenhagen.pay.common.error.PayErrorCode;
import com.copenhagen.pay.common.error.PayException;
import com.copenhagen.pay.payment.credential.mandate.ArtifactEncryptionService;
import com.copenhagen.pay.payment.pg.PaymentGateway.ChargeResult;
import com.copenhagen.pay.payment.pg.PaymentGateway.ChargeResult.Outcome;
import com.copenhagen.pay.payment.policy.PaymentStatus;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/** PG 결과를 세 개의 결제 원장에 반영하고 성공 시에만 Receipt를 발급합니다. */
@Service
public class PaymentExecutionSettlementService {

    private static final String DEFAULT_RECONCILIATION_REASON = "PG_TIMEOUT";
    private static final long RECONCILIATION_DELAY_SECONDS = 60;

    private final PaymentExecutionRepository repository;
    private final PaymentLifecycleService lifecycleService;
    private final ArtifactEncryptionService encryptionService;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public PaymentExecutionSettlementService(
            PaymentExecutionRepository repository,
            PaymentLifecycleService lifecycleService,
            ArtifactEncryptionService encryptionService,
            ObjectMapper objectMapper,
            Clock clock) {
        this.repository = repository;
        this.lifecycleService = lifecycleService;
        this.encryptionService = encryptionService;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Transactional
    public PaymentExecutionResult settle(
            PreparedPaymentExecution prepared,
            ChargeResult chargeResult) {
        PaymentExecutionRecord current = repository.findByIdForUpdate(prepared.paymentId())
                .orElseThrow(() -> new PayException(PayErrorCode.RESOURCE_NOT_FOUND));
        if (current.status() != PaymentStatus.PROCESSING) {
            return PaymentExecutionResult.from(current);
        }
        if (!prepared.attemptId().equals(current.attemptId())) {
            throw new PayException(
                    PayErrorCode.INTERNAL_ERROR,
                    "결제 시도 원장을 찾을 수 없습니다.",
                    current.status().name(),
                    PayErrorCode.INTERNAL_ERROR.defaultNextAction());
        }

        ChargeResult normalized = normalize(chargeResult);
        PaymentStatus targetStatus = toPaymentStatus(normalized.outcome());
        Instant now = clock.instant();
        String errorCode = targetStatus == PaymentStatus.SUCCESS
                ? null
                : responseCodeOrDefault(normalized.responseCode(), targetStatus);

        if (!repository.updateAttemptResult(
                prepared.attemptId(),
                targetStatus.name(),
                errorCode,
                now)) {
            throw persistenceConflict(current.status());
        }
        if (!repository.updatePgTransactionResult(
                prepared.attemptId(),
                targetStatus.name(),
                normalized.pgTransactionId(),
                normalized.responseCode(),
                now,
                now)) {
            throw persistenceConflict(current.status());
        }

        if (targetStatus == PaymentStatus.SUCCESS) {
            lifecycleService.resolveSuccess(current.id());
            insertReceipt(current, prepared, normalized, now);
        } else if (targetStatus == PaymentStatus.FAILED) {
            lifecycleService.resolveDefinitiveFailure(current.id());
        } else {
            lifecycleService.markUnknown(current.id());
            repository.insertReconciliationJob(
                    prepared.attemptId(),
                    reconciliationReason(normalized),
                    now.plusSeconds(RECONCILIATION_DELAY_SECONDS));
        }

        return repository.findById(current.id())
                .map(PaymentExecutionResult::from)
                .orElseThrow(() -> new PayException(PayErrorCode.RESOURCE_NOT_FOUND));
    }

    private void insertReceipt(
            PaymentExecutionRecord payment,
            PreparedPaymentExecution prepared,
            ChargeResult result,
            Instant issuedAt) {
        Map<String, Object> receiptBody = new LinkedHashMap<>();
        receiptBody.put("paymentId", payment.id());
        receiptBody.put("attemptId", prepared.attemptId());
        receiptBody.put("pgTransactionId", result.pgTransactionId());
        receiptBody.put("amountMinor", payment.amountMinor());
        receiptBody.put("currency", payment.currency());
        receiptBody.put("status", PaymentStatus.SUCCESS.name());
        receiptBody.put("issuedAt", issuedAt.toString());

        String plaintext;
        try {
            plaintext = objectMapper.writeValueAsString(receiptBody);
        } catch (JacksonException | IllegalArgumentException exception) {
            throw new PayException(
                    PayErrorCode.INTERNAL_ERROR,
                    "결제 영수증을 생성할 수 없습니다.",
                    PaymentStatus.SUCCESS.name(),
                    PayErrorCode.INTERNAL_ERROR.defaultNextAction(),
                    exception);
        }
        ArtifactEncryptionService.EncryptedArtifact encrypted = encryptionService.encrypt(plaintext);
        repository.insertReceipt(new PaymentExecutionRepository.PaymentReceipt(
                UUID.randomUUID().toString(),
                payment.id(),
                encrypted.hash(),
                encrypted.ciphertext(),
                issuedAt));
    }

    private ChargeResult normalize(ChargeResult result) {
        if (result == null || result.outcome() == null) {
            return ChargeResult.unknown("PG_INVALID_RESPONSE");
        }
        if (result.outcome() == Outcome.SUCCESS
                && (result.pgTransactionId() == null || result.pgTransactionId().isBlank())) {
            return ChargeResult.unknown("PG_INVALID_SUCCESS_RESPONSE");
        }
        return result;
    }

    private PaymentStatus toPaymentStatus(Outcome outcome) {
        return switch (outcome) {
            case SUCCESS -> PaymentStatus.SUCCESS;
            case FAILED -> PaymentStatus.FAILED;
            case UNKNOWN -> PaymentStatus.UNKNOWN;
        };
    }

    private String responseCodeOrDefault(String responseCode, PaymentStatus status) {
        if (responseCode != null && !responseCode.isBlank()) {
            return responseCode;
        }
        return status == PaymentStatus.FAILED ? "PG_DECLINED" : "PG_TIMEOUT";
    }

    private String reconciliationReason(ChargeResult result) {
        if (result.responseCode() == null || result.responseCode().isBlank()) {
            return DEFAULT_RECONCILIATION_REASON;
        }
        return result.responseCode();
    }

    private PayException persistenceConflict(PaymentStatus currentStatus) {
        return new PayException(
                PayErrorCode.STATE_CONFLICT,
                PayErrorCode.STATE_CONFLICT.defaultMessage(),
                currentStatus.name(),
                PayErrorCode.STATE_CONFLICT.defaultNextAction());
    }
}
