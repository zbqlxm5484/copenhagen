package com.agenticcommerce.merchantmock;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;

class UcpMerchantMockApplicationTest {

    @Test
    void applicationEntryPointExists() {
        assertNotNull(UcpMerchantMockApplication.class);
    }
}
