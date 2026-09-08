package com.copenhagen.pay.common.idempotency;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.copenhagen.pay.common.error.ApiErrorWriter;
import com.copenhagen.pay.common.web.PayRequestContext;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.ObjectMapper;

class PayIdempotencyFilterTest {

    private final PayIdempotencyFilter filter = new PayIdempotencyFilter(
            new IdempotencyKeyResolver(),
            new ApiErrorWriter(new ObjectMapper()));

    @Test
    void stateChangingProtectedRequestRequiresKey() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(
                "POST",
                "/api/v1/payment-approvals");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertEquals(400, response.getStatus());
        assertTrue(response.getContentAsString().contains("IDEMPOTENCY_KEY_REQUIRED"));
    }

    @Test
    void keyIsStoredOnRequestContext() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(
                "POST",
                "/api/v1/payment-approvals");
        request.addHeader("Idempotency-Key", "request-001");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertEquals(200, response.getStatus());
        assertEquals(
                "request-001",
                PayRequestContext.findIdempotencyKey(request).orElseThrow().value());
    }
}
