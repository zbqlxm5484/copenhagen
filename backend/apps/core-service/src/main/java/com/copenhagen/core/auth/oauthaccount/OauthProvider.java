package com.copenhagen.core.auth.oauthaccount;

public enum OauthProvider {

    GOOGLE("google");

    private final String value;

    OauthProvider(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    public static OauthProvider from(String value) {
        for (OauthProvider provider : values()) {
            if (provider.value.equals(value)) {
                return provider;
            }
        }
        throw new IllegalArgumentException("Unknown OAuth provider: " + value);
    }
}
