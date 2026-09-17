package com.copenhagen.core.order;

import java.util.UUID;

/**
 * {@code purchase_requests} 한 행. {@code public_id}가 API의 {@code validationId}이고, merchant에 보내는
 * {@code purchase_request_id}이자 Merchant Authorization의 claim이다.
 */
public record PurchaseRequest(long id, UUID publicId, long userId, long cartId, String status) {

    public static final String STATUS_CREATING_CHECKOUTS = "CREATING_CHECKOUTS";
    public static final String STATUS_FAILED = "FAILED";

    /** 앱에서 들어온 구매 요청. MCP 유입 경로는 tool 등록 이후에 생긴다. */
    public static final String CHANNEL_APP = "APP";
}
