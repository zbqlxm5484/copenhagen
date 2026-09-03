package com.agenticcommerce.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;

class CoreServiceApplicationTest {

    @Test
    void applicationEntryPointExists() {
        assertNotNull(CoreServiceApplication.class);
    }
}
