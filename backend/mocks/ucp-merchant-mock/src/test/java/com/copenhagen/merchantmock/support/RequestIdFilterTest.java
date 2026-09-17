package com.copenhagen.merchantmock.support;

import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 요청 하나의 requestId 수명(DESIGN.md 12.1절). 받은 것을 쓰고, 없으면 만들고, 끝나면 지운다.
 */
class RequestIdFilterTest {

    private final RequestIdFilter filter = new RequestIdFilter();

    @AfterEach
    void tearDown() {
        MDC.clear();
    }

    @Test
    @DisplayName("Core 가 보낸 X-Request-Id 를 MDC 에 넣고 응답 헤더로 되돌려 준다")
    void doFilter_withHeader_usesItAndEchoes() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/ucp/v1/merchant-a/checkout-sessions");
        request.addHeader(RequestIdFilter.HEADER, "core-req-42");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> seen = new AtomicReference<>();

        filter.doFilter(request, response, (req, res) -> seen.set(MDC.get(LogContext.REQUEST_ID)));

        assertEquals("core-req-42", seen.get(), "체인 안에서는 MDC 에 있어야 한다");
        assertEquals("core-req-42", response.getHeader(RequestIdFilter.HEADER));
        assertNull(MDC.get(LogContext.REQUEST_ID), "요청이 끝나면 지운다. 서블릿 스레드는 재사용된다");
    }

    @Test
    @DisplayName("헤더가 없으면 만들어서 쓰고 그 값을 응답 헤더로 알려 준다")
    void doFilter_withoutHeader_generates() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/ucp/v1/merchant-a/orders/order-1");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> seen = new AtomicReference<>();

        filter.doFilter(request, response, (req, res) -> seen.set(MDC.get(LogContext.REQUEST_ID)));

        assertNotNull(seen.get());
        assertEquals(seen.get(), response.getHeader(RequestIdFilter.HEADER));
    }

    @Test
    @DisplayName("빈 값이나 100자를 넘는 값은 믿지 않고 새로 만든다")
    void resolve_blankOrTooLong_generates() {
        assertNotEquals("", RequestIdFilter.resolve("   "));
        String tooLong = "x".repeat(RequestIdFilter.MAX_LENGTH + 1);
        assertNotEquals(tooLong, RequestIdFilter.resolve(tooLong));
        assertEquals("ok", RequestIdFilter.resolve(" ok "));
    }

    @Test
    @DisplayName("체인이 예외로 끝나도 MDC 는 지운다")
    void doFilter_chainThrows_stillClears() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/x");
        request.addHeader(RequestIdFilter.HEADER, "req-1");
        MockHttpServletResponse response = new MockHttpServletResponse();

        try {
            filter.doFilter(request, response, (req, res) -> {
                throw new IllegalStateException("boom");
            });
        } catch (Exception expected) {
            // 예외는 그대로 올라간다. 여기서 보는 것은 뒷정리다
        }

        assertNull(MDC.get(LogContext.REQUEST_ID));
    }
}
