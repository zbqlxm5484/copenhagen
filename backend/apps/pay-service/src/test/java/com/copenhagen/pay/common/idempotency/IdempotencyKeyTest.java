package com.copenhagen.pay.common.idempotency;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.copenhagen.pay.common.error.PayErrorCode;
import com.copenhagen.pay.common.error.PayException;
import org.junit.jupiter.api.Test;

class IdempotencyKeyTest {

    @Test
    void blankKeyIsRejected() {
        PayException exception = assertThrows(
                PayException.class,
                () -> new IdempotencyKey(" "));

        assertEquals(PayErrorCode.IDEMPOTENCY_KEY_INVALID, exception.errorCode());
    }

    @Test
    void controlCharacterIsRejected() {
        PayException exception = assertThrows(
                PayException.class,
                () -> new IdempotencyKey("key\nvalue"));

        assertEquals(PayErrorCode.IDEMPOTENCY_KEY_INVALID, exception.errorCode());
    }
}
