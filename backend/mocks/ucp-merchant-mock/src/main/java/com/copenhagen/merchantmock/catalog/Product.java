package com.copenhagen.merchantmock.catalog;

import java.util.List;

/**
 * 메모리에 올려 두는 상품 한 건. DB 의 products 한 행에 대응한다(DESIGN.md 5.4절).
 *
 * <p>옵션과 재고는 variants 로 흡수했다. 상품 수십 개 규모라 전량 메모리 로드가 조인보다 싸다(13절).
 *
 * <p>MVP 는 재고를 차감하지 않는다. 품절은 시나리오 API 로 stock 을 0 으로 주입해 만든다.
 */
public record Product(
        String id,
        String externalId,
        String title,
        String description,
        String imageUrl,
        long basePrice,
        String currency,
        ProductStatus status,
        List<Variant> variants) {

    /** 하나라도 살 수 있는 옵션이 있으면 재고 있음으로 본다. */
    public boolean inStock() {
        return status == ProductStatus.ACTIVE && variants.stream().anyMatch(Variant::purchasable);
    }

    public String availability() {
        return inStock() ? "IN_STOCK" : "OUT_OF_STOCK";
    }

    /** 옵션 추가금을 더하기 전 노출가. 탐색용이고 최종 금액은 Checkout 이 정한다. */
    public long displayPrice() {
        return basePrice;
    }

    public enum ProductStatus {
        ACTIVE,
        DISCONTINUED
    }

    public record Variant(
            String sku,
            String optionName,
            String optionValue,
            long priceDelta,
            int stock,
            ProductStatus status) {

        public boolean purchasable() {
            return status == ProductStatus.ACTIVE && stock > 0;
        }
    }
}
