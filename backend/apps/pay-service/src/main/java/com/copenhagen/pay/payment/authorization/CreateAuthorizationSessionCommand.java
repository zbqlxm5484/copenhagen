package com.copenhagen.pay.payment.authorization;

public record CreateAuthorizationSessionCommand(
        String purchaseRequestId,
        String userId,
        String merchantId,
        String checkoutId,
        String checkoutHash,
        String checkoutJwt,
        AuthorizationDisplaySnapshot displaySnapshot,
        long amountMinor,
        String currency) {

}
