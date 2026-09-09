package com.copenhagen.pay.payment.authorization;

import tools.jackson.databind.JsonNode;

public record CreateAuthorizationSessionCommand(
        String purchaseRequestId,
        String userId,
        String merchantId,
        String checkoutId,
        String checkoutDigest,
        JsonNode checkoutArtifact,
        AuthorizationDisplaySnapshot displaySnapshot,
        long amountMinor,
        String currency) {
}
