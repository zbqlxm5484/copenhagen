package com.copenhagen.core.internalapi.auth;

import com.copenhagen.core.agent.AgentScopes;

/** 내부 API 경로 → 필요한 scope(설계서 §3.3). 목록에 없는 경로는 scope를 요구하지 않는다(404로 떨어진다). */
public final class InternalScopes {

    public static final String PREFIX = "/internal/v1/";

    private static final String CATALOG_PREFIX = PREFIX + "catalog/";
    private static final String MERCHANTS_PREFIX = PREFIX + "merchants/";
    private static final String CART_PATH = PREFIX + "carts/current";
    private static final String CART_ITEMS_PREFIX = CART_PATH + "/items";
    private static final String PURCHASE_REQUESTS_PATH = PREFIX + "purchase-requests";

    private InternalScopes() {
    }

    public static boolean isInternalPath(String path) {
        return path != null && path.startsWith(PREFIX);
    }

    public static String requiredScope(String path) {
        if (path.startsWith(CATALOG_PREFIX) || path.startsWith(MERCHANTS_PREFIX)) {
            return AgentScopes.CATALOG_READ;
        }
        if (path.startsWith(CART_ITEMS_PREFIX)) {
            return AgentScopes.CART_WRITE;
        }
        if (CART_PATH.equals(path)) {
            return AgentScopes.CART_READ;
        }
        if (PURCHASE_REQUESTS_PATH.equals(path)) {
            return AgentScopes.PURCHASE_HANDOFF;
        }
        return null;
    }
}
