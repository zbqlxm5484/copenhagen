package com.copenhagen.core.payment;

import java.util.List;

import com.copenhagen.core.checkout.MerchantCheckoutMessages.Checkout;
import com.copenhagen.core.payment.PaymentApprovalMessages.CreateApprovalRequest;
import com.copenhagen.core.payment.PaymentApprovalMessages.DisplaySnapshot;
import com.copenhagen.core.payment.PaymentApprovalMessages.SummaryItem;

/** 최초 호출과 장애 복구가 같은 승인 대상과 멱등키를 사용한다. */
public final class PaymentApprovalRequests {

    private PaymentApprovalRequests() {
    }

    public static String idempotencyKey(String purchaseRequestId, String merchantId) {
        return "payment-approval-create-" + purchaseRequestId + "-" + merchantId;
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
