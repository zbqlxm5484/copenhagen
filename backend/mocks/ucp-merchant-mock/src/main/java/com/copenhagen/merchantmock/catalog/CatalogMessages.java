package com.copenhagen.merchantmock.catalog;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

/** Catalog 세 엔드포인트의 요청·응답. 필드 표기는 Core 확정본을 따라 camelCase 다. */
public final class CatalogMessages {

    private CatalogMessages() {
    }

    public record SearchRequest(String query, Filters filters) {

        public record Filters(Long maxPrice, String currency) {
        }
    }

    public record LookupRequest(List<String> productIds) {
    }

    public record ProductRequest(String productId) {
    }

    /** 검색과 일괄 조회가 같이 쓴다. 결과가 없으면 빈 배열이고 404 를 내지 않는다. */
    public record ItemsResponse(List<Item> items) {
    }

    public record Item(String productId, String title, Price price, String availability) {

        static Item of(Product product) {
            return new Item(
                    product.externalId(),
                    product.title(),
                    new Price(product.displayPrice(), product.currency()),
                    product.availability());
        }
    }

    public record Price(long amount, String currency) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ProductDetail(
            String productId,
            String title,
            String description,
            Price price,
            List<Option> options,
            String availability) {

        static ProductDetail of(Product product) {
            return new ProductDetail(
                    product.externalId(),
                    product.title(),
                    product.description(),
                    new Price(product.displayPrice(), product.currency()),
                    Option.from(product),
                    product.availability());
        }
    }

    /**
     * 옵션 묶음. 같은 optionName 끼리 모은다.
     *
     * <p>Core 확정본의 예시는 name 과 values 둘뿐이다. 재고와 추가금은 Checkout 이 확정하므로
     * 여기서는 살 수 없는 옵션을 빼는 것으로만 반영한다.
     */
    public record Option(String name, List<OptionValue> values) {

        static List<Option> from(Product product) {
            return product.variants().stream()
                    .filter(Product.Variant::purchasable)
                    .collect(java.util.stream.Collectors.groupingBy(
                            Product.Variant::optionName,
                            java.util.LinkedHashMap::new,
                            java.util.stream.Collectors.toList()))
                    .entrySet().stream()
                    .map(entry -> new Option(
                            entry.getKey(),
                            entry.getValue().stream()
                                    .map(v -> new OptionValue(v.sku(), v.optionValue()))
                                    .toList()))
                    .toList();
        }
    }

    public record OptionValue(String optionValueId, String label) {
    }
}
