package com.copenhagen.contract.internalapi.fraud;

import java.util.List;

/** Shape validation only; fraud policy belongs to the owning application. */
final class FraudSchemaValidation {

    private FraudSchemaValidation() {
    }

    static void requiredText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }

    static void optionalText(String value, String field) {
        if (value != null) {
            requiredText(value, field);
        }
    }

    static List<String> texts(List<String> values, String field) {
        if (values == null) {
            return List.of();
        }
        for (String value : values) {
            requiredText(value, field + " entry");
        }
        return List.copyOf(values);
    }
}
