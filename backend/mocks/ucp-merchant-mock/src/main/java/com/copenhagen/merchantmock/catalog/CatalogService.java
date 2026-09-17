package com.copenhagen.merchantmock.catalog;

import java.util.List;
import java.util.Locale;

import com.copenhagen.merchantmock.support.ApiException;
import com.copenhagen.merchantmock.support.MerchantProperties;
import org.springframework.stereotype.Service;

/**
 * Catalog Search·Lookup·Product. 몰 하나 안에서만 찾는다(393).
 *
 * <p>여기서 주는 가격과 재고는 탐색용 현재 정보다. 최종 거래 조건의 원본은 Checkout 이다.
 * Agent 가 이 값을 그대로 믿고 결제를 시도해도 Core 가 Checkout 값으로 다시 확정한다(DESIGN.md 1절).
 *
 * <p>DISCONTINUED 상품은 세 엔드포인트 모두에서 숨긴다. 파는 물건이 아니다.
 */
@Service
public class CatalogService {

    private final CatalogStore store;
    private final MerchantProperties merchants;

    public CatalogService(CatalogStore store, MerchantProperties merchants) {
        this.store = store;
        this.merchants = merchants;
    }

    public CatalogMessages.ItemsResponse search(String merchantId, CatalogMessages.SearchRequest request) {
        merchants.requireMall(merchantId);
        String keyword = request == null || request.query() == null
                ? ""
                : request.query().trim().toLowerCase(Locale.ROOT);
        CatalogMessages.SearchRequest.Filters filters = request == null ? null : request.filters();
        List<CatalogMessages.Item> items = store.all(merchantId).stream()
                .filter(CatalogService::onSale)
                .filter(product -> matchesKeyword(product, keyword))
                .filter(product -> matchesPrice(product, filters))
                .filter(product -> matchesCurrency(product, filters))
                .map(CatalogMessages.Item::of)
                .toList();
        return new CatalogMessages.ItemsResponse(items);
    }

    /** 없는 ID 는 조용히 빠진다. 404 를 내지 않는다. Core 가 개수를 비교해 판단한다. */
    public CatalogMessages.ItemsResponse lookup(String merchantId, CatalogMessages.LookupRequest request) {
        merchants.requireMall(merchantId);
        if (request == null || request.productIds() == null || request.productIds().isEmpty()) {
            return new CatalogMessages.ItemsResponse(List.of());
        }
        List<CatalogMessages.Item> items = request.productIds().stream()
                .map(productId -> store.byExternalId(merchantId, productId))
                .flatMap(java.util.Optional::stream)
                .filter(CatalogService::onSale)
                .map(CatalogMessages.Item::of)
                .toList();
        return new CatalogMessages.ItemsResponse(items);
    }

    public CatalogMessages.ProductDetail product(String merchantId, CatalogMessages.ProductRequest request) {
        merchants.requireMall(merchantId);
        String productId = request == null ? null : request.productId();
        if (productId == null || productId.isBlank()) {
            throw ApiException.productNotFound("(빈 productId)");
        }
        return store.byExternalId(merchantId, productId)
                .filter(CatalogService::onSale)
                .map(CatalogMessages.ProductDetail::of)
                .orElseThrow(() -> ApiException.productNotFound(productId));
    }

    private static boolean onSale(Product product) {
        return product.status() == Product.ProductStatus.ACTIVE;
    }

    private static boolean matchesKeyword(Product product, String keyword) {
        if (keyword.isEmpty()) {
            return true;
        }
        return product.title().toLowerCase(Locale.ROOT).contains(keyword);
    }

    private static boolean matchesPrice(Product product, CatalogMessages.SearchRequest.Filters filters) {
        if (filters == null || filters.maxPrice() == null) {
            return true;
        }
        return product.displayPrice() <= filters.maxPrice();
    }

    private static boolean matchesCurrency(Product product, CatalogMessages.SearchRequest.Filters filters) {
        if (filters == null || filters.currency() == null || filters.currency().isBlank()) {
            return true;
        }
        return product.currency().equalsIgnoreCase(filters.currency());
    }
}
