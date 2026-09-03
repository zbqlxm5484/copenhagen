package com.copenhagen.mcp;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;

class McpServerApplicationTest {

    @Test
    void applicationEntryPointExists() {
        assertNotNull(McpServerApplication.class);
    }
}
