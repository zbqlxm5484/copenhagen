package com.copenhagen.pay.common.idempotency;

import com.copenhagen.pay.common.error.PayErrorCode;
import com.copenhagen.pay.common.error.PayException;

public record IdempotencyKey(String value) {

    public static final int MAX_LENGTH = 100;

    public IdempotencyKey {
        if (value == null || value.isBlank() || value.length() > MAX_LENGTH) {
            throw new PayException(PayErrorCode.IDEMPOTENCY_KEY_INVALID);
        }
        if (value.chars().anyMatch(character -> character < 0x21 || character > 0x7E)) {
            throw new PayException(PayErrorCode.IDEMPOTENCY_KEY_INVALID);
        }
    }
}
