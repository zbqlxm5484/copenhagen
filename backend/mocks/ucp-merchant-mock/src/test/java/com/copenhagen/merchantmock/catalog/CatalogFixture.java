package com.copenhagen.merchantmock.catalog;

import java.util.List;

/** V3__seed_products.sql 과 같은 모양의 시드. 테스트가 DB 없이 돌게 한다. */
public final class CatalogFixture {

    private CatalogFixture() {
    }

    public static List<Product> seed() {
        return List.of(
                product("product-101", "러닝화 A", 89000, Product.ProductStatus.ACTIVE, List.of(
                        variant("product-101-260", "size", "260", 0, 12, Product.ProductStatus.ACTIVE),
                        variant("product-101-270", "size", "270", 0, 7, Product.ProductStatus.ACTIVE),
                        variant("product-101-280", "size", "280", 0, 0, Product.ProductStatus.ACTIVE))),
                product("product-102", "경량 바람막이", 129000, Product.ProductStatus.ACTIVE, List.of(
                        variant("product-102-m-black", "size", "M", 0, 20, Product.ProductStatus.ACTIVE),
                        variant("product-102-l-black", "size", "L", 0, 15, Product.ProductStatus.ACTIVE),
                        variant("product-102-xl-black", "size", "XL", 5000, 4, Product.ProductStatus.ACTIVE))),
                product("product-103", "무선 이어폰", 219000, Product.ProductStatus.ACTIVE, List.of(
                        variant("product-103-white", "color", "화이트", 0, 30, Product.ProductStatus.ACTIVE),
                        variant("product-103-black", "color", "블랙", 0, 25, Product.ProductStatus.ACTIVE))),
                product("product-104", "보온 텀블러 500ml", 27000, Product.ProductStatus.ACTIVE, List.of(
                        variant("product-104-single", "type", "기본", 0, 100, Product.ProductStatus.ACTIVE))),
                product("product-105", "단종된 백팩", 74000, Product.ProductStatus.DISCONTINUED, List.of(
                        variant("product-105-navy", "color", "네이비", 0, 0,
                                Product.ProductStatus.DISCONTINUED))));
    }

    /** 모든 옵션이 품절인 상품. 시나리오 OUT_OF_STOCK 을 흉내 낸다. */
    public static Product soldOut() {
        return product("product-199", "품절된 러닝화", 99000, Product.ProductStatus.ACTIVE, List.of(
                variant("product-199-260", "size", "260", 0, 0, Product.ProductStatus.ACTIVE)));
    }

    private static Product product(
            String externalId, String title, long basePrice,
            Product.ProductStatus status, List<Product.Variant> variants) {
        return new Product(
                externalId + "-uuid", "merchant-a", externalId, title, null,
                "https://cdn.example.test/" + externalId + ".jpg",
                basePrice, "KRW", status, variants);
    }

    private static Product.Variant variant(
            String sku, String optionName, String optionValue,
            long priceDelta, int stock, Product.ProductStatus status) {
        return new Product.Variant(sku, optionName, optionValue, priceDelta, stock, status);
    }
}
