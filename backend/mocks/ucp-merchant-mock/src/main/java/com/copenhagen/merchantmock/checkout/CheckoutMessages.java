package com.copenhagen.merchantmock.checkout;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 체크아웃 세 엔드포인트의 요청·응답. 필드 표기는 Core 확정본을 따라 snake_case 다.
 *
 * <p>응답 {@link Checkout} 은 그대로 직렬화해 checkout_sessions.canonical_checkout 에 넣는다.
 * 175 에서 서명할 때 JCS 정규화 결과로 덮어쓴다. 값이 없으면 키를 생략한다(DESIGN.md 17절).
 */
public final class CheckoutMessages {

    private CheckoutMessages() {
    }

    public record CheckoutRequest(
            @JsonProperty("line_items") List<LineItemRequest> lineItems,
            Fulfillment fulfillment) {

        public record LineItemRequest(
                @JsonProperty("product_id") String productId,
                @JsonProperty("option_value_ids") List<String> optionValueIds,
                Integer quantity) {
        }

        public record Fulfillment(String type, Destination destination) {
        }

        @JsonInclude(JsonInclude.Include.NON_NULL)
        public record Destination(
                String recipient,
                @JsonProperty("postal_code") String postalCode,
                @JsonProperty("address_line1") String addressLine1,
                @JsonProperty("address_line2") String addressLine2) {
        }
    }

    /** 생성·조회·수정이 같이 쓰는 최신 스냅샷. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Checkout(
            String id,
            String status,
            String currency,
            @JsonProperty("line_items") List<LineItem> lineItems,
            FulfillmentResult fulfillment,
            List<Total> totals,
            List<Message> messages,
            @JsonProperty("expires_at") String expiresAt) {

        /**
         * 저장된 스냅샷은 그대로 두고 나가는 응답의 상태만 바꾼다(DESIGN.md 7.1절).
         *
         * <p>canonical_checkout 은 서명 대상이라 불변 조건 5·6 이 고정한다. 취소·만료로 상태가 달라져도
         * 저장된 바이트를 다시 쓰지 않고 이 자리에서만 덮는다.
         */
        Checkout withStatus(String newStatus) {
            return new Checkout(id, newStatus, currency, lineItems, fulfillment, totals, messages, expiresAt);
        }
    }

    /**
     * 취소 응답. 전체 체크아웃이 아니라 두 필드뿐이다(docs/API_SPEC.md).
     *
     * <p>취소에 금액과 항목을 다시 실을 이유가 없다.
     */
    public record CancelResult(String id, String status) {
    }

    public record LineItem(String id, Item item, int quantity) {
    }

    public record Item(String id, String title, long price) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record FulfillmentResult(String type, String status, Long amount) {
    }

    public record Total(String type, long amount) {
    }

    /**
     * incomplete 사유. 형태는 DESIGN.md 7.1절의 {code, severity, message, target}.
     * severity 가 error 면 ready_for_complete 로 갈 수 없다. warning 은 알림만이다.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Message(String code, String severity, String message, String target) {

        static Message error(String code, String message, String target) {
            return new Message(code, "error", message, target);
        }

        static Message warning(String code, String message, String target) {
            return new Message(code, "warning", message, target);
        }

        boolean blocking() {
            return "error".equals(severity);
        }
    }
}
