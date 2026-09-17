package com.copenhagen.pay.payment.notification;

import com.copenhagen.pay.payment.policy.PaymentStatus;

public record NotificationMessage(
        String eventId,
        NotificationEventType type,
        String approvalId,
        String paymentId,
        String status,
        String nextAction) {

    static String nextAction(PaymentStatus status) {
        return status == PaymentStatus.UNKNOWN
                ? "WAIT_FOR_PAYMENT_STATUS"
                : "SHOW_PAYMENT_RESULT";
    }

    @Override
    public String toString() {
        return "NotificationMessage["
                + "eventId=<redacted>"
                + ", type=" + type
                + ", approvalId=<redacted>"
                + ", paymentId=<redacted>"
                + ", status=" + status
                + ", nextAction=" + nextAction
                + ']';
    }
}
