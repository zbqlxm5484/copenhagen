package com.copenhagen.pay.common.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.copenhagen.pay.common.error.ApiErrorWriter;
import com.copenhagen.pay.common.web.PayRequestContext;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.ObjectMapper;

class PayAuthenticationFilterTest {

    private final ApiErrorWriter errorWriter = new ApiErrorWriter(new ObjectMapper());

    @Test
    void userTokenIsAcceptedOnAppApi() throws Exception {
        PayAuthenticationFilter filter = filterWith(Map.of("user-token", "user-001"), Map.of());
        MockHttpServletRequest request = request("GET", "/api/pay/v1/payment-approvals");
        request.addHeader("Authorization", "Bearer user-token");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertEquals(200, response.getStatus());
        PayPrincipal principal = PayRequestContext.requirePrincipal(request);
        assertEquals(PayPrincipal.Type.USER, principal.type());
        assertEquals("user-001", principal.subject());
    }

    @Test
    void serviceTokenIsAcceptedOnInternalApi() throws Exception {
        PayAuthenticationFilter filter = filterWith(Map.of(), Map.of("core-token", "core-service"));
        MockHttpServletRequest request = request("POST", "/internal/v1/payment-approvals");
        request.addHeader("Authorization", "Bearer core-token");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertEquals(200, response.getStatus());
        assertEquals(
                PayPrincipal.Type.SERVICE,
                PayRequestContext.requirePrincipal(request).type());
    }

    @Test
    void missingTokenReturnsUnauthorizedError() throws Exception {
        PayAuthenticationFilter filter = filterWith(Map.of(), Map.of());
        MockHttpServletRequest request = request("GET", "/api/pay/v1/payment-approvals");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertEquals(401, response.getStatus());
        assertNotNull(response.getContentAsString());
        assertEquals(true, response.getContentAsString().contains("AUTHENTICATION_REQUIRED"));
    }

    @Test
    void userTokenCannotCallInternalApi() throws Exception {
        PayAuthenticationFilter filter = filterWith(Map.of("user-token", "user-001"), Map.of());
        MockHttpServletRequest request = request("POST", "/internal/v1/payment-approvals");
        request.addHeader("Authorization", "Bearer user-token");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertEquals(403, response.getStatus());
        assertInstanceOf(String.class, response.getContentAsString());
        assertEquals(true, response.getContentAsString().contains("FORBIDDEN"));
    }

    private PayAuthenticationFilter filterWith(
            Map<String, String> userTokens,
            Map<String, String> serviceTokens) {
        PayAuthProperties properties = new PayAuthProperties(userTokens, serviceTokens);
        return new PayAuthenticationFilter(
                new ConfiguredBearerTokenVerifier(properties),
                errorWriter);
    }

    private MockHttpServletRequest request(String method, String path) {
        return new MockHttpServletRequest(method, path);
    }
}
