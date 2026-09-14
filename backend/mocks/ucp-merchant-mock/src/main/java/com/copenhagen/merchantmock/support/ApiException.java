package com.copenhagen.merchantmock.support;

import org.springframework.http.HttpStatus;

/**
 * 오류 코드를 달고 나가는 예외. 코드 목록은 DESIGN.md 10절이 소유한다.
 *
 * <p>응답 형식은 팀 API 명세 v4 의 네 필드다({@link ErrorResponse}). {@code nextAction} 값은
 * 설계서 20장 표에서 가져온다. 표에 없는 코드는 성격이 같은 기존 값을 고르고 그 근거를 여기 남긴다.
 */
public class ApiException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String errorCode;
    private final HttpStatus status;
    private final String currentState;
    private final String nextAction;

    public ApiException(String errorCode, HttpStatus status, String message,
                        String currentState, String nextAction) {
        super(message);
        this.errorCode = errorCode;
        this.status = status;
        this.currentState = currentState;
        this.nextAction = nextAction;
    }

    /**
     * 없거나 단종된 상품. 사용자가 할 수 있는 일은 장바구니로 돌아가 다른 상품을 고르는 것이다.
     * 설계서 표의 {@code CHECKOUT_STOCK_INSUFFICIENT} 와 같은 상황이라 같은 값을 쓴다.
     */
    public static ApiException productNotFound(String productId) {
        return new ApiException("PRODUCT_NOT_FOUND", HttpStatus.NOT_FOUND,
                "상품을 찾을 수 없다: " + productId, null, "RETURN_TO_CART");
    }

    /** 없는 체크아웃. 이어갈 대상이 없으니 처음부터 다시 시작한다. */
    public static ApiException checkoutNotFound(String checkoutId) {
        return new ApiException("CHECKOUT_NOT_FOUND", HttpStatus.NOT_FOUND,
                "체크아웃을 찾을 수 없다: " + checkoutId, null, "RETURN_TO_CART");
    }

    /**
     * 완료·취소된 체크아웃을 수정하거나 취소하려 했다. 이미 끝난 건이라 먼저 상태를 확인해야 한다.
     * 설계서 표의 {@code COMPLETION_UNKNOWN} 과 같은 안내다.
     */
    public static ApiException checkoutNotModifiable(String checkoutId, Enum<?> status) {
        String state = status.name().toLowerCase(java.util.Locale.ROOT);
        return new ApiException("CHECKOUT_NOT_MODIFIABLE", HttpStatus.CONFLICT,
                "이 상태에서는 체크아웃을 수정할 수 없다: " + checkoutId + " (" + status + ")",
                state, "CHECK_PURCHASE_STATUS");
    }

    public String errorCode() {
        return errorCode;
    }

    public HttpStatus status() {
        return status;
    }

    public String currentState() {
        return currentState;
    }

    public String nextAction() {
        return nextAction;
    }
}
