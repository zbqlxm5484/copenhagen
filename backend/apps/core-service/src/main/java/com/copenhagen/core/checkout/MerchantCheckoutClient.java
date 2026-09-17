package com.copenhagen.core.checkout;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import com.copenhagen.core.checkout.MerchantCheckoutMessages.Checkout;
import com.copenhagen.core.checkout.MerchantCheckoutMessages.CheckoutRequest;
import com.copenhagen.core.common.error.CoreErrorCode;
import com.copenhagen.core.common.error.CoreException;

/**
 * Merchant Checkout(UCP {@code /ucp/v1/checkout-sessions}) 전용 경계. merchant 하나당 인스턴스 하나다.
 *
 * <p>생성 멱등은 merchant가 흡수한다 — {@code Idempotency-Key}에서 UUIDv5로 checkout id를 만들기 때문에
 * 같은 키의 재시도는 같은 체크아웃을 돌려받는다. Core는 키를 결정적으로 만들기만 하면 된다.
 */
public class MerchantCheckoutClient {

    static final String CHECKOUT_SESSIONS_PATH = "/ucp/v1/checkout-sessions";
    static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";

    private static final Logger log = LoggerFactory.getLogger(MerchantCheckoutClient.class);

    private final String merchantId;
    private final RestClient restClient;

    public MerchantCheckoutClient(String merchantId, RestClient restClient) {
        this.merchantId = merchantId;
        this.restClient = restClient;
    }

    public String merchantId() {
        return merchantId;
    }

    public Checkout create(CheckoutRequest request, String idempotencyKey) {
        try {
            return restClient.post()
                    .uri(CHECKOUT_SESSIONS_PATH)
                    .header(IDEMPOTENCY_KEY_HEADER, idempotencyKey)
                    .body(request)
                    .retrieve()
                    .body(Checkout.class);
        } catch (HttpClientErrorException.NotFound exception) {
            // merchant는 없거나 단종된 상품에만 404를 준다. 계산서 자체를 만들 수 없는 경우다.
            throw new CoreException(CoreErrorCode.PRODUCT_NOT_FOUND);
        } catch (RestClientException exception) {
            log.warn("Merchant Checkout 생성 실패: merchantId={}", merchantId, exception);
            throw new CoreException(
                    CoreErrorCode.MERCHANT_CHECKOUT_UNAVAILABLE,
                    CoreErrorCode.MERCHANT_CHECKOUT_UNAVAILABLE.defaultMessage(),
                    null,
                    CoreErrorCode.MERCHANT_CHECKOUT_UNAVAILABLE.defaultNextAction(),
                    exception);
        }
    }
}
