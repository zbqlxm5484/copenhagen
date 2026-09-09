package com.copenhagen.contract.internalapi.fraud;

import java.time.Instant;
import java.util.Currency;
import java.util.List;
import java.util.Objects;

/**
 * Shared purchase-intent snapshot, not a persisted entity or a user approval.
 * maxTotalAmount is an integer in the currency's minor unit.
 * Nullable constraints preserve unspecified intent; they never imply unlimited authorization.
 */
public record PurchaseIntent(
        String intentId,
        String userId,
        List<String> productKeywords,
        String category,
        String brand,
        Integer quantity,
        Long maxTotalAmount,
        String currency,
        String purpose,
        List<String> allowedMerchants,
        List<String> constraints,
        Instant createdAt,
        Instant expiresAt) {

    public PurchaseIntent {
        FraudSchemaValidation.requiredText(intentId, "intentId");
        FraudSchemaValidation.requiredText(userId, "userId");
        productKeywords = FraudSchemaValidation.texts(productKeywords, "productKeywords");
        allowedMerchants = FraudSchemaValidation.texts(allowedMerchants, "allowedMerchants");
        constraints = FraudSchemaValidation.texts(constraints, "constraints");
        FraudSchemaValidation.optionalText(category, "category");
        FraudSchemaValidation.optionalText(brand, "brand");
        FraudSchemaValidation.optionalText(purpose, "purpose");
        if (quantity != null && quantity <= 0) {
            throw new IllegalArgumentException("quantity must be positive when specified");
        }
        if (maxTotalAmount != null && maxTotalAmount < 0) {
            throw new IllegalArgumentException("maxTotalAmount must not be negative");
        }
        if (maxTotalAmount != null || currency != null) {
            FraudSchemaValidation.requiredText(currency, "currency");
            if (!currency.matches("[A-Z]{3}")) {
                throw new IllegalArgumentException("currency must be an uppercase ISO 4217 code");
            }
            Currency.getInstance(currency);
        }
        Objects.requireNonNull(createdAt, "createdAt");
        if (expiresAt != null && !expiresAt.isAfter(createdAt)) {
            throw new IllegalArgumentException("expiresAt must be after createdAt");
        }
    }
}
