package com.copenhagen.pay.payment.authorization;

import com.copenhagen.contract.internalapi.ApprovalStatus;
import com.copenhagen.contract.internalapi.PaymentApprovalNextAction;
import com.copenhagen.contract.internalapi.PaymentApprovalStatusResponse;
import com.copenhagen.pay.common.error.PayErrorCode;
import com.copenhagen.pay.common.error.PayException;
import com.copenhagen.pay.common.validation.RequestValidation;
import com.copenhagen.pay.payment.policy.AuthorizationSessionStatus;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class PaymentApprovalStatusService {

    private static final int APPROVAL_ID_MAX_LENGTH = 36;

    private final PaymentApprovalStatusRepository repository;

    public PaymentApprovalStatusService(PaymentApprovalStatusRepository repository) {
        this.repository = repository;
    }

    public PaymentApprovalStatusResponse find(String approvalId) {
        String normalizedApprovalId = normalizeApprovalId(approvalId);
        PaymentApprovalStatusRepository.PaymentApprovalStatusView view = repository
                .findByApprovalId(normalizedApprovalId)
                .orElseThrow(() -> new PayException(PayErrorCode.RESOURCE_NOT_FOUND));

        return new PaymentApprovalStatusResponse(
                view.approvalId(),
                ApprovalStatus.valueOf(view.approvalStatus().name()),
                view.paymentId(),
                view.paymentStatus() == null
                        ? null
                        : com.copenhagen.contract.internalapi.PaymentStatus.valueOf(
                                view.paymentStatus().name()),
                view.amountMinor(),
                view.currency(),
                nextAction(view));
    }

    private PaymentApprovalNextAction nextAction(
            PaymentApprovalStatusRepository.PaymentApprovalStatusView view) {
        if (view.paymentStatus() == null) {
            return view.approvalStatus() == AuthorizationSessionStatus.APPROVED
                    ? PaymentApprovalNextAction.WAIT_FOR_PAYMENT_CREATION
                    : null;
        }
        return switch (view.paymentStatus()) {
            case READY, PROCESSING, UNKNOWN -> PaymentApprovalNextAction.WAIT_FOR_PAYMENT_STATUS;
            case SUCCESS, FAILED -> null;
        };
    }

    private String normalizeApprovalId(String approvalId) {
        String validatedApprovalId = RequestValidation.requireLengthAtMost(
                approvalId,
                "approvalId",
                APPROVAL_ID_MAX_LENGTH);
        try {
            UUID uuid = UUID.fromString(validatedApprovalId);
            if (!uuid.toString().equalsIgnoreCase(validatedApprovalId)) {
                throw invalidApprovalId();
            }
            return uuid.toString();
        } catch (IllegalArgumentException exception) {
            throw new PayException(
                    PayErrorCode.INVALID_REQUEST,
                    "approvalId 형식이 올바르지 않습니다.",
                    null,
                    "CHECK_APPROVAL_ID",
                    exception);
        }
    }

    private PayException invalidApprovalId() {
        return new PayException(
                PayErrorCode.INVALID_REQUEST,
                "approvalId 형식이 올바르지 않습니다.",
                null,
                "CHECK_APPROVAL_ID");
    }
}
