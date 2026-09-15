package com.copenhagen.pay.payment.credential.mandate;

import com.copenhagen.pay.common.error.PayErrorCode;
import com.copenhagen.pay.common.error.PayException;
import com.copenhagen.pay.common.idempotency.IdempotencyGuard;
import com.copenhagen.pay.common.validation.RequestValidation;
import com.copenhagen.pay.payment.authorization.CheckoutHashCodec;
import com.copenhagen.pay.payment.policy.PaymentStatus;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PaymentCredentialConsumptionService {

    private static final String KRW = "KRW";

    private final PaymentCredentialConsumptionRepository repository;
    private final IdempotencyGuard idempotencyGuard;
    private final Clock clock;

    public PaymentCredentialConsumptionService(
            PaymentCredentialConsumptionRepository repository,
            IdempotencyGuard idempotencyGuard,
            Clock clock) {
        this.repository = repository;
        this.idempotencyGuard = idempotencyGuard;
        this.clock = clock;
    }

    @Transactional
    public CreatedPayment consume(ConsumeCommand command) {
        validate(command);
        CreatedPayment existing = repository.findPaymentByMerchantAndIdempotencyKey(
                        command.merchantAccountId(),
                        command.idempotencyKey())
                .orElse(null);
        if (existing != null) {
            idempotencyGuard.check(
                    existing.requestHash(),
                    command.requestHash(),
                    existing.status().name());
            return existing;
        }

        PaymentCredentialRecord credential = repository
                .findCredentialForUpdate(command.credentialId())
                .orElseThrow(() -> new PayException(PayErrorCode.RESOURCE_NOT_FOUND));

        // A concurrent request may have committed the Payment while this request
        // was waiting for the Credential row lock. Re-read the idempotent result
        // before interpreting the now-consumed Credential as a conflict.
        CreatedPayment concurrentExisting = repository.findPaymentByMerchantAndIdempotencyKey(
                command.merchantAccountId(),
                command.idempotencyKey()).orElse(null);
        if (concurrentExisting != null) {
            idempotencyGuard.check(
                    concurrentExisting.requestHash(),
                    command.requestHash(),
                    concurrentExisting.status().name());
            return concurrentExisting;
        }

        validateCredential(credential, command);

        Instant now = clock.instant();
        CreatedPayment payment = new CreatedPayment(
                UUID.randomUUID().toString(),
                credential.merchantAccountId(),
                credential.externalCheckoutId(),
                credential.purchaseRequestId(),
                credential.id(),
                credential.amountMinor(),
                credential.currency(),
                PaymentStatus.READY,
                command.idempotencyKey(),
                command.requestHash());
        repository.insertPayment(payment);
        if (!repository.consumeCredential(credential.id(), now)) {
            throw new PayException(
                    PayErrorCode.CREDENTIAL_ALREADY_CONSUMED,
                    PayErrorCode.CREDENTIAL_ALREADY_CONSUMED.defaultMessage(),
                    credential.status().name(),
                    PayErrorCode.CREDENTIAL_ALREADY_CONSUMED.defaultNextAction());
        }
        return payment;
    }

    private void validate(ConsumeCommand command) {
        if (command == null) {
            throw new PayException(PayErrorCode.INVALID_REQUEST);
        }
        RequestValidation.requireLengthAtMost(command.merchantAccountId(), "merchantAccountId", 36);
        RequestValidation.requireLengthAtMost(command.merchantId(), "merchantId", 100);
        RequestValidation.requireLengthAtMost(command.externalCheckoutId(), "checkoutId", 150);
        RequestValidation.requireLengthAtMost(command.credentialId(), "credentialId", 36);
        RequestValidation.requireLengthAtMost(command.idempotencyKey(), "idempotencyKey", 100);
        RequestValidation.requireLengthAtMost(command.requestHash(), "requestHash", 64);
        CheckoutHashCodec.toInternalDigest(command.checkoutHash());
        if (command.amountMinor() < 0 || !KRW.equals(command.currency())) {
            throw new PayException(
                    PayErrorCode.INVALID_REQUEST,
                    "결제 금액 또는 통화가 올바르지 않습니다.",
                    null,
                    "CHECK_PAYMENT_AMOUNT");
        }
    }

    private void validateCredential(
            PaymentCredentialRecord credential,
            ConsumeCommand command) {
        if (credential.status() == PaymentCredentialStatus.CONSUMED) {
            throw new PayException(
                    PayErrorCode.CREDENTIAL_ALREADY_CONSUMED,
                    PayErrorCode.CREDENTIAL_ALREADY_CONSUMED.defaultMessage(),
                    credential.status().name(),
                    PayErrorCode.CREDENTIAL_ALREADY_CONSUMED.defaultNextAction());
        }
        if (credential.status() == PaymentCredentialStatus.REVOKED) {
            throw new PayException(
                    PayErrorCode.PAYMENT_CREDENTIAL_REVOKED,
                    PayErrorCode.PAYMENT_CREDENTIAL_REVOKED.defaultMessage(),
                    credential.status().name(),
                    PayErrorCode.PAYMENT_CREDENTIAL_REVOKED.defaultNextAction());
        }
        if (credential.checkoutMandateStatus() == MandateStatus.REVOKED
                || credential.paymentMandateStatus() == MandateStatus.REVOKED) {
            throw new PayException(
                    PayErrorCode.PAYMENT_CREDENTIAL_REVOKED,
                    PayErrorCode.PAYMENT_CREDENTIAL_REVOKED.defaultMessage(),
                    credential.status().name(),
                    PayErrorCode.PAYMENT_CREDENTIAL_REVOKED.defaultNextAction());
        }
        if (credential.status() == PaymentCredentialStatus.EXPIRED
                || credential.checkoutMandateStatus() == MandateStatus.EXPIRED
                || credential.paymentMandateStatus() == MandateStatus.EXPIRED
                || credential.isExpired(clock.instant())) {
            throw new PayException(
                    PayErrorCode.PAYMENT_CREDENTIAL_EXPIRED,
                    PayErrorCode.PAYMENT_CREDENTIAL_EXPIRED.defaultMessage(),
                    credential.status().name(),
                    PayErrorCode.PAYMENT_CREDENTIAL_EXPIRED.defaultNextAction());
        }

        String commandDigest = CheckoutHashCodec.toInternalDigest(command.checkoutHash());
        if (!credential.merchantAccountId().equals(command.merchantAccountId())
                || !credential.merchantId().equals(command.merchantId())
                || !credential.externalCheckoutId().equals(command.externalCheckoutId())
                || !credential.checkoutDigest().equals(commandDigest)
                || credential.amountMinor() != command.amountMinor()
                || !credential.currency().equals(command.currency())) {
            throw new PayException(
                    PayErrorCode.CREDENTIAL_SCOPE_MISMATCH,
                    PayErrorCode.CREDENTIAL_SCOPE_MISMATCH.defaultMessage(),
                    credential.status().name(),
                    PayErrorCode.CREDENTIAL_SCOPE_MISMATCH.defaultNextAction());
        }
    }

    public record ConsumeCommand(
            String merchantAccountId,
            String merchantId,
            String externalCheckoutId,
            String checkoutHash,
            long amountMinor,
            String currency,
            String credentialId,
            String idempotencyKey,
            String requestHash) {
    }
}
