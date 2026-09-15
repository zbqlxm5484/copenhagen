package com.copenhagen.core.common.auth;

import java.util.Optional;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;

public final class CoreRequestContext {

    private static final String PRINCIPAL_ATTRIBUTE = CoreRequestContext.class.getName() + ".principal";

    private CoreRequestContext() {
    }

    public static void setPrincipal(HttpServletRequest request, UUID userPublicId) {
        request.setAttribute(PRINCIPAL_ATTRIBUTE, userPublicId);
    }

    public static Optional<UUID> findPrincipal(HttpServletRequest request) {
        return Optional.ofNullable(request.getAttribute(PRINCIPAL_ATTRIBUTE)).map(UUID.class::cast);
    }
}
