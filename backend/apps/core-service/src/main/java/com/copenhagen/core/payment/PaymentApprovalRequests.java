package com.copenhagen.core.payment;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import com.copenhagen.core.checkout.MerchantCheckoutMessages.Checkout;
import com.copenhagen.core.payment.PaymentApprovalMessages.CreateApprovalRequest;
import com.copenhagen.core.payment.PaymentApprovalMessages.DisplaySnapshot;
import com.copenhagen.core.payment.PaymentApprovalMessages.SummaryItem;

/** 최초 호출과 장애 복구가 같은 승인 대상과 멱등키를 사용한다. */
public final class PaymentApprovalRequests {

    private PaymentApprovalRequests() {
    }

    /** Pay는 상태 변경 경계에서 UUID가 아닌 키를 거절하므로 이름 기반 UUID로 만든다(pay-idempotency.md). */
    public static String idempotencyKey(String purchaseRequestId, String merchantId) {
        return UUID.nameUUIDFromBytes(
                ("copenhagen-pay-approval:" + purchaseRequestId + ":" + merchantId)
                        .getBytes(StandardCharsets.UTF_8))
                .toString();
    }

    /** 구매 요청별로 고정된 키. 재시도·재전송이 같은 값을 써야 인계 알림이 1건으로 유지된다. */
    public static String purchaseNotificationIdempotencyKey(String purchaseRequestId) {
        return UUID.nameUUIDFromBytes(
                ("copenhagen-pay-purchase-handoff:" + purchaseRequestId)
                        .getBytes(StandardCharsets.UTF_8))
                .toString();
    }

    public static CreateApprovalRequest create(String purchaseRequestId, String userId, String merchantId,
            String merchantName, String checkoutDigest, Checkout checkout) {
        List<SummaryItem> summary = checkout.lineItems().stream()
                .map(line -> new SummaryItem(line.item().title(), null, line.quantity(), line.item().price()))
                .toList();
        return new CreateApprovalRequest(purchaseRequestId, userId, merchantId, checkout.id(),
                PayCheckoutHash.toExternal(checkoutDigest), checkout.merchantAuthorization(),
                new DisplaySnapshot(merchantName, summary), checkout.amountOf("total"), checkout.currency());
    }
}
