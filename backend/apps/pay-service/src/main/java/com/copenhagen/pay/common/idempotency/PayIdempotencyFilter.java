package com.copenhagen.pay.common.idempotency;

import com.copenhagen.pay.common.auth.PayAuthenticationPolicy;
import com.copenhagen.pay.common.error.ApiErrorWriter;
import com.copenhagen.pay.common.error.PayException;
import com.copenhagen.pay.common.web.PayRequestContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class PayIdempotencyFilter extends OncePerRequestFilter {

    private final IdempotencyKeyResolver keyResolver;
    private final ApiErrorWriter errorWriter;

    public PayIdempotencyFilter(
            IdempotencyKeyResolver keyResolver,
            ApiErrorWriter errorWriter) {
        this.keyResolver = keyResolver;
        this.errorWriter = errorWriter;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (!request.getDispatcherType().equals(jakarta.servlet.DispatcherType.REQUEST)) {
            return true;
        }
        if (PayAuthenticationPolicy.requiredPrincipal(request.getRequestURI())
                == PayAuthenticationPolicy.RequiredPrincipal.NONE) {
            return true;
        }
        return switch (request.getMethod()) {
            case "GET", "HEAD", "OPTIONS", "TRACE" -> true;
            default -> false;
        };
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        try {
            PayRequestContext.setIdempotencyKey(request, keyResolver.require(request));
            filterChain.doFilter(request, response);
        } catch (PayException exception) {
            errorWriter.write(response, exception);
        }
    }
}
