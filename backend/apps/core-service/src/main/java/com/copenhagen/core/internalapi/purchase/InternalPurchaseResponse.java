package com.copenhagen.core.internalapi.purchase;

/**
 * {@code prepare_purchase_handoff} 응답(설계서 §4.7).
 *
 * <p>mcp-server가 문자 단위로 대조하는 값들이다 — {@code appLink}는 자기 설정
 * {@code MCP_APP_LINK_BASE} + id와 같아야 하고, {@code status}·{@code nextAction}은 고정 문자열,
 * {@code cartId}·{@code cartVersion}은 요청 값 그대로여야 하며 {@code expiresAt}은 31분을 넘지 않아야 한다.
 */
public record InternalPurchaseResponse(
        String purchaseRequestId,
        String status,
        String cartId,
        int cartVersion,
        String expiresAt,
        String appLink,
        String nextAction) {

    public static final String STATUS_PENDING_ADDRESS = "PENDING_ADDRESS";
    public static final String NEXT_ACTION_SELECT_ADDRESS = "SELECT_SHIPPING_ADDRESS";
}
