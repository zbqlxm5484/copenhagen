package com.copenhagen.pay.common.auth;

public record PayPrincipal(Type type, String subject) {

    public PayPrincipal {
        if (type == null || subject == null || subject.isBlank()) {
            throw new IllegalArgumentException("Authentication principal is incomplete");
        }
    }

    public enum Type {
        USER,
        SERVICE
    }
}
