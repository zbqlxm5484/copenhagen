package com.copenhagen.core.product;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import com.copenhagen.core.common.error.CoreErrorCode;
import com.copenhagen.core.common.error.CoreException;

/**
 * Merchant Catalog(UCP {@code /ucp/v1/catalog/*}) 전용 경계. merchant 하나당 인스턴스 하나(자기 자신의
 * id·displayName을 알고 있어 {@link Offer} 조립에 그대로 쓴다).
 *
 * <p>여기서 주는 가격·재고는 탐색용 현재 정보다. 최종 거래 조건은 Checkout이 정한다(ADR-0004).
 * Core는 이 응답을 재계산하지 않고 그대로 전달한다.
 */
class MerchantCatalogClient {

    private static final Logger log = LoggerFactory.getLogger(MerchantCatalogClient.class);

    private final String merchantId;
    private final String merchantName;
    private final RestClient restClient;

    MerchantCatalogClient(String merchantId, String merchantName, RestClient restClient) {
        this.merchantId = merchantId;
        this.merchantName = merchantName;
        this.restClient = restClient;
    }

    String merchantId() {
        return merchantId;
    }

    String merchantName() {
        return merchantName;
    }

    List<CatalogItem> search(String query, Long maxPrice, String currency) {
        try {
            CatalogItemsResponse response = restClient.post()
                    .uri("/ucp/v1/catalog/search")
                    .body(new CatalogSearchRequest(query, new CatalogFilters(maxPrice, currency)))
                    .retrieve()
                    .body(CatalogItemsResponse.class);
            return response == null ? List.of() : response.items();
        } catch (RestClientException exception) {
            throw merchantUnavailable(exception);
        }
    }

    CatalogProductDetail product(String productId) {
        try {
            return restClient.post()
                    .uri("/ucp/v1/catalog/product")
                    .body(new CatalogProductRequest(productId))
                    .retrieve()
                    .body(CatalogProductDetail.class);
        } catch (HttpClientErrorException.NotFound exception) {
            throw new CoreException(CoreErrorCode.PRODUCT_NOT_FOUND);
        } catch (RestClientException exception) {
            throw merchantUnavailable(exception);
        }
    }

    private CoreException merchantUnavailable(RestClientException exception) {
        log.warn("Merchant Catalog 호출 실패: merchantId={}", merchantId, exception);
        return new CoreException(
                CoreErrorCode.MERCHANT_CATALOG_UNAVAILABLE,
                CoreErrorCode.MERCHANT_CATALOG_UNAVAILABLE.defaultMessage(),
                null,
                CoreErrorCode.MERCHANT_CATALOG_UNAVAILABLE.defaultNextAction(),
                exception);
    }

    // Merchant 응답 계약을 그대로 옮긴 내부 전용 레코드. CatalogMessages(ucp-merchant-mock)와 필드가 1:1 대응한다.

    record CatalogSearchRequest(String query, CatalogFilters filters) {
    }

    record CatalogFilters(Long maxPrice, String currency) {
    }

    record CatalogProductRequest(String productId) {
    }

    record CatalogItemsResponse(List<CatalogItem> items) {
    }

    record CatalogItem(String productId, String title, CatalogPrice price, String availability) {
    }

    record CatalogPrice(long amount, String currency) {
    }

    record CatalogProductDetail(
            String productId,
            String title,
            String description,
            CatalogPrice price,
            List<CatalogOption> options,
            String availability) {
    }

    record CatalogOption(String name, List<CatalogOptionValue> values) {
    }

    record CatalogOptionValue(String optionValueId, String label) {
    }
}
