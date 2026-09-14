package com.copenhagen.pay.common.auth;

import com.copenhagen.pay.common.error.ApiErrorWriter;
import com.copenhagen.pay.common.error.PayErrorCode;
import com.copenhagen.pay.common.error.PayException;
import com.copenhagen.pay.common.web.PayRequestContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class PayAuthenticationFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";

    private final PayTokenVerifier tokenVerifier;
    private final ApiErrorWriter errorWriter;
    private final PayRouteAuthorizer routeAuthorizer;

    public PayAuthenticationFilter(
            PayTokenVerifier tokenVerifier,
            ApiErrorWriter errorWriter) {
        this(tokenVerifier, errorWriter, PayRouteAuthorizer.allowAllForTests());
    }

    @Autowired
    public PayAuthenticationFilter(
            PayTokenVerifier tokenVerifier,
            ApiErrorWriter errorWriter,
            PayRouteAuthorizer routeAuthorizer) {
        this.tokenVerifier = tokenVerifier;
        this.errorWriter = errorWriter;
        this.routeAuthorizer = routeAuthorizer;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (!request.getDispatcherType().equals(jakarta.servlet.DispatcherType.REQUEST)) {
            return true;
        }
        return PayAuthenticationPolicy.requiredPrincipal(request.getRequestURI())
                == PayAuthenticationPolicy.RequiredPrincipal.NONE;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        try {
            String bearerToken = extractBearerToken(request.getHeader(HttpHeaders.AUTHORIZATION));
            PayPrincipal principal = verifyToken(bearerToken);
            routeAuthorizer.authorize(request.getRequestURI(), principal);
            PayRequestContext.setPrincipal(request, principal);
            filterChain.doFilter(request, response);
        } catch (PayException exception) {
            errorWriter.write(response, exception);
        }
    }

    private String extractBearerToken(String authorizationHeader) {
        if (authorizationHeader == null || authorizationHeader.isBlank()) {
            throw new PayException(PayErrorCode.AUTHENTICATION_REQUIRED);
        }
        if (!authorizationHeader.regionMatches(
                true,
                0,
                BEARER_PREFIX,
                0,
                BEARER_PREFIX.length())) {
            throw new PayException(PayErrorCode.INVALID_TOKEN);
        }

        String token = authorizationHeader.substring(BEARER_PREFIX.length()).trim();
        if (token.isEmpty()) {
            throw new PayException(PayErrorCode.INVALID_TOKEN);
        }
        return token;
    }

    private PayPrincipal verifyToken(String bearerToken) {
        Optional<PayPrincipal> principal = tokenVerifier.verify(bearerToken);
        return principal.orElseThrow(() -> new PayException(PayErrorCode.INVALID_TOKEN));
    }

}
