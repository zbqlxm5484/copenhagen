package com.copenhagen.pay.common.auth;

public final class PayAuthenticationPolicy {

    private static final String APP_PREFIX = "/api/pay/v1";
    private static final String INTERNAL_PREFIX = "/internal/v1";

    private PayAuthenticationPolicy() {
    }

    public static RequiredPrincipal requiredPrincipal(String path) {
        if (matches(path, APP_PREFIX)) {
            return RequiredPrincipal.USER;
        }
        if (matches(path, INTERNAL_PREFIX)) {
            return RequiredPrincipal.SERVICE;
        }
        return RequiredPrincipal.NONE;
    }

    private static boolean matches(String path, String prefix) {
        return prefix.equals(path) || path.startsWith(prefix + "/");
    }

    public enum RequiredPrincipal {
        USER,
        SERVICE,
        NONE
    }
}
