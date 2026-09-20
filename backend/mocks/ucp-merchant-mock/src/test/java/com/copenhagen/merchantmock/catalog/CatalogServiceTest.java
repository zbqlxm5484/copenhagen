package com.copenhagen.merchantmock.catalog;

import com.copenhagen.merchantmock.support.MerchantPropertiesFixture;
import java.util.ArrayList;
import java.util.List;

import com.copenhagen.contract.ucp.CatalogOptionValue;


import com.copenhagen.merchantmock.support.ApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Catalog Search·Lookup·Product 단위 테스트. DB 없이 시드 목록만으로 돌린다. */
class CatalogServiceTest {

    @Test
    void productDetail_includesEffectiveVariantPriceAndStock() {
        Product product = new Product("sample", "merchant-a", "kurly:1000025270", "상품", "설명", null, null, null,
                10500L, "KRW", Product.ProductStatus.ACTIVE, List.of(new Product.Variant(
                        "kurly:1000025270-M00000008360", "종류", "추가 구성", 9100L, 100, Product.ProductStatus.ACTIVE)));
        CatalogOptionValue option = CatalogMessages.ProductDetail.of(product).options().get(0).values().get(0);
        assertEquals(19600L, option.priceAmount());
        assertEquals(100, option.stockQuantity());
    }


    private CatalogService service;

    private static CatalogService serviceOf(List<Product> products) {
        CatalogStore store = new CatalogStore(() -> products);
        store.load();
        return new CatalogService(store, MerchantPropertiesFixture.defaults());
    }

    private static List<String> idsOf(CatalogMessages.ItemsResponse response) {
        List<String> ids = new ArrayList<>();
        response.items().forEach(item -> ids.add(item.productId()));
        return ids;
    }

    @BeforeEach
    void setUp() {
        this.service = serviceOf(CatalogFixture.seed());
    }

    @Test
    @DisplayName("검색과 상세 응답에 상품 이미지가 실린다")
    void search_and_product_includeImageUrl() {
        // given & when
        CatalogMessages.ItemsResponse response = service.search("merchant-a",
                new CatalogMessages.SearchRequest("러닝화", null));
        CatalogMessages.ProductDetail detail = service.product("merchant-a",
                new CatalogMessages.ProductRequest("product-101"));

        // then 428 이전에는 두 응답 어디에도 이미지가 없어 Agent 가 그림을 못 받았다
        assertEquals("https://cdn.example.test/product-101.jpg", response.items().get(0).imageUrl());
        assertEquals("https://cdn.example.test/product-101.jpg", detail.imageUrl());
    }

    @Test
    @DisplayName("빈 검색어는 판매 중인 상품을 전부 돌려준다")
    void search_blankQuery_returnsAllActiveProducts() {
        // given & when
        CatalogMessages.ItemsResponse response =
                service.search("merchant-a", new CatalogMessages.SearchRequest("", null));

        // then
        assertEquals(List.of("product-101", "product-102", "product-103", "product-104"), idsOf(response));
    }

    @Test
    @DisplayName("제목 부분일치로 검색한다")
    void search_partialTitle_returnsMatchingProducts() {
        // given & when
        CatalogMessages.ItemsResponse response =
                service.search("merchant-a", new CatalogMessages.SearchRequest("러닝화", null));

        // then
        assertEquals(List.of("product-101"), idsOf(response));
    }

    @Test
    @DisplayName("십만원 이하 러닝화 검색이 시드 상품을 돌려준다")
    void search_priceAndCurrencyFilter_returnsSeededProduct() {
        // given & when
        CatalogMessages.ItemsResponse response = service.search("merchant-a", new CatalogMessages.SearchRequest(
                "러닝화", new CatalogMessages.SearchRequest.Filters(100000L, "KRW")));

        // then
        assertEquals(1, response.items().size());
        CatalogMessages.Item item = response.items().get(0);
        assertEquals("product-101", item.productId());
        assertEquals(89000L, item.price().amount());
        assertEquals("KRW", item.price().currency());
        assertEquals("IN_STOCK", item.availability());
    }

    @Test
    @DisplayName("상한가를 넘는 상품은 빠진다")
    void search_maxPriceFilter_excludesExpensiveProducts() {
        // given & when
        CatalogMessages.ItemsResponse response = service.search("merchant-a", new CatalogMessages.SearchRequest(
                "", new CatalogMessages.SearchRequest.Filters(100000L, null)));

        // then
        assertEquals(List.of("product-101", "product-104"), idsOf(response));
    }

    @Test
    @DisplayName("통화가 다르면 빠진다")
    void search_otherCurrency_returnsEmpty() {
        // given & when
        CatalogMessages.ItemsResponse response = service.search("merchant-a", new CatalogMessages.SearchRequest(
                "", new CatalogMessages.SearchRequest.Filters(null, "USD")));

        // then
        assertTrue(response.items().isEmpty());
    }

    @Test
    @DisplayName("결과가 없으면 빈 배열이다")
    void search_noMatch_returnsEmpty() {
        // given & when
        CatalogMessages.ItemsResponse response =
                service.search("merchant-a", new CatalogMessages.SearchRequest("없는상품", null));

        // then
        assertTrue(response.items().isEmpty());
    }

    @Test
    @DisplayName("단종 상품은 검색에 나오지 않는다")
    void search_discontinuedProduct_returnsEmpty() {
        // given & when
        CatalogMessages.ItemsResponse response =
                service.search("merchant-a", new CatalogMessages.SearchRequest("백팩", null));

        // then
        assertTrue(response.items().isEmpty());
    }

    @Test
    @DisplayName("모든 옵션이 품절이면 OUT_OF_STOCK 이다")
    void search_allVariantsSoldOut_marksOutOfStock() {
        // given & when
        CatalogService soldOutService = serviceOf(List.of(CatalogFixture.soldOut()));

        CatalogMessages.ItemsResponse response =
                soldOutService.search("merchant-a", new CatalogMessages.SearchRequest("", null));

        // then
        assertEquals("OUT_OF_STOCK", response.items().get(0).availability());
    }

    @Test
    @DisplayName("일괄조회는 요청한 순서로 돌려준다")
    void lookup_multipleIds_keepsRequestedOrder() {
        // given & when
        CatalogMessages.ItemsResponse response = service.lookup("merchant-a",
                new CatalogMessages.LookupRequest(List.of("product-103", "product-101")));

        // then
        assertEquals(List.of("product-103", "product-101"), idsOf(response));
    }

    @Test
    @DisplayName("일괄조회에서 없는 ID 는 조용히 빠진다")
    void lookup_unknownId_dropsItSilently() {
        // given & when
        CatalogMessages.ItemsResponse response = service.lookup("merchant-a",
                new CatalogMessages.LookupRequest(List.of("product-101", "product-999", "product-105")));

        // then
        assertEquals(List.of("product-101"), idsOf(response));
    }

    @Test
    @DisplayName("일괄조회에 빈 목록을 주면 빈 배열이다")
    void lookup_emptyRequest_returnsEmpty() {
        // when & then
        assertTrue(service.lookup("merchant-a", new CatalogMessages.LookupRequest(List.of())).items().isEmpty());
        assertTrue(service.lookup("merchant-a", new CatalogMessages.LookupRequest(null)).items().isEmpty());
        assertTrue(service.lookup("merchant-a", null).items().isEmpty());
    }

    @Test
    @DisplayName("상세조회는 옵션을 묶어서 돌려준다")
    void product_existingId_groupsOptions() {
        // given & when
        CatalogMessages.ProductDetail detail =
                service.product("merchant-a", new CatalogMessages.ProductRequest("product-101"));

        // then
        assertEquals("product-101", detail.productId());
        assertEquals(89000L, detail.price().amount());
        assertEquals(1, detail.options().size());
        assertEquals("size", detail.options().get(0).name());
        assertEquals(List.of("260", "270"),
                detail.options().get(0).values().stream().map(CatalogOptionValue::label).toList());
    }

    @Test
    @DisplayName("품절 옵션은 상세조회에서 빠진다")
    void product_soldOutVariant_isHidden() {
        // given & when
        CatalogMessages.ProductDetail detail =
                service.product("merchant-a", new CatalogMessages.ProductRequest("product-101"));

        List<String> skus = detail.options().get(0).values().stream()
                .map(CatalogOptionValue::optionValueId).toList();

        // then
        assertFalse(skus.contains("product-101-280"), "stock 이 0 인 SKU 는 노출하지 않는다");
    }

    @Test
    @DisplayName("옵션이 여러 묶음이면 묶음별로 나눈다")
    void product_multipleOptionGroups_splitsByName() {
        // given & when
        CatalogMessages.ProductDetail detail =
                service.product("merchant-a", new CatalogMessages.ProductRequest("product-103"));

        // then
        assertEquals(1, detail.options().size());
        assertEquals("color", detail.options().get(0).name());
        assertEquals(2, detail.options().get(0).values().size());
    }

    @Test
    @DisplayName("없는 상품 상세조회는 PRODUCT_NOT_FOUND 다")
    void product_unknownId_throwsProductNotFound() {
        // when & then
        ApiException thrown = assertThrows(ApiException.class,
                () -> service.product("merchant-a", new CatalogMessages.ProductRequest("product-999")));

        assertEquals("PRODUCT_NOT_FOUND", thrown.errorCode());
        assertEquals(404, thrown.status().value());
    }

    @Test
    @DisplayName("단종 상품 상세조회도 PRODUCT_NOT_FOUND 다")
    void product_discontinuedId_throwsProductNotFound() {
        // when & then
        ApiException thrown = assertThrows(ApiException.class,
                () -> service.product("merchant-a", new CatalogMessages.ProductRequest("product-105")));

        assertEquals("PRODUCT_NOT_FOUND", thrown.errorCode());
    }

    @Test
    @DisplayName("productId 가 비면 PRODUCT_NOT_FOUND 다")
    void product_blankId_throwsProductNotFound() {
        // when & then
        assertThrows(ApiException.class, () -> service.product("merchant-a", new CatalogMessages.ProductRequest("")));
        assertThrows(ApiException.class, () -> service.product("merchant-a", new CatalogMessages.ProductRequest(null)));
        assertThrows(ApiException.class, () -> service.product("merchant-a", null));
    }

    @Test
    @DisplayName("다른 몰에서는 그 상품이 보이지 않고, 모르는 몰은 404 MERCHANT_NOT_FOUND 다")
    void search_otherOrUnknownMall_isScoped() {
        // given: 시드는 전부 merchant-a 다
        CatalogMessages.SearchRequest all = new CatalogMessages.SearchRequest("", null);

        // when & then: merchant-b 는 등록된 몰이지만 상품이 없다
        assertTrue(service.search("merchant-b", all).items().isEmpty());
        assertTrue(service.lookup("merchant-b", new CatalogMessages.LookupRequest(List.of("product-101"))).items()
                .isEmpty());
        ApiException notInMall = assertThrows(ApiException.class,
                () -> service.product("merchant-b", new CatalogMessages.ProductRequest("product-101")));
        assertEquals("PRODUCT_NOT_FOUND", notInMall.errorCode());

        // 모르는 몰은 상품을 보기 전에 거절한다
        ApiException unknown = assertThrows(ApiException.class, () -> service.search("merchant-z", all));
        assertEquals("MERCHANT_NOT_FOUND", unknown.errorCode());
        assertEquals(404, unknown.status().value());
    }

    @Test
    @DisplayName("동의어 묶음으로 운동화를 치면 러닝화도 나온다")
    void search_synonymGroup_findsOtherWordsForTheSameThing() {
        // given: 시드에는 "러닝화 A" 가 있고 "운동화" 라는 낱말은 어디에도 없다
        // when
        CatalogMessages.ItemsResponse response =
                service.search("merchant-a", new CatalogMessages.SearchRequest("운동화", null));

        // then 470 이전에는 제목 부분문자열 하나뿐이라 0건이었다
        assertEquals(List.of("product-101"), idsOf(response));
    }

    @Test
    @DisplayName("제목에서 맞은 상품이 카테고리로만 맞은 상품보다 앞에 온다")
    void search_titleMatch_outranksCategoryOnlyMatch() {
        // given: product-201 은 제목에 "운동화" 가 없고 카테고리에만 있다
        List<Product> products = new ArrayList<>(CatalogFixture.seed());
        products.add(CatalogFixture.categoryOnlyMatch());
        CatalogService ranked = serviceOf(products);

        // when
        CatalogMessages.ItemsResponse response =
                ranked.search("merchant-a", new CatalogMessages.SearchRequest("운동화", null));

        // then 첫 화면이 pageSize 4 라 순서가 곧 결과다
        assertEquals(List.of("product-101", "product-201"), idsOf(response));
    }

    @Test
    @DisplayName("낱말 여러 개는 전부 맞아야 하고 브랜드에서 맞아도 된다")
    void search_multipleTokens_requiresAllAndLooksAtBrand() {
        // given & when: "나이키" 는 브랜드에만, "러닝화" 는 제목에만 있다
        CatalogMessages.ItemsResponse matched =
                service.search("merchant-a", new CatalogMessages.SearchRequest("나이키 러닝화", null));
        CatalogMessages.ItemsResponse unmatched =
                service.search("merchant-a", new CatalogMessages.SearchRequest("아디다스 러닝화", null));

        // then 470 이전에는 통짜 부분문자열이라 둘 다 0건이었다
        assertEquals(List.of("product-101"), idsOf(matched));
        assertTrue(unmatched.items().isEmpty(), "브랜드는 맞아도 제목이 안 맞으면 빠진다");
    }

    @Test
    @DisplayName("낱말로 맞은 상품이 부분문자열로만 맞은 상품보다 앞에 온다")
    void search_wordBoundaryMatch_outranksSubstringMatch() {
        // given: 우유팩 키링이 시드에서 먼저다. 부분문자열만 보면 실제 우유와 구분되지 않는다
        CatalogService ranked = serviceOf(CatalogFixture.milkAndLookalikes());

        // when
        CatalogMessages.ItemsResponse response =
                ranked.search("merchant-a", new CatalogMessages.SearchRequest("우유", null));

        // then
        assertEquals(List.of("product-302", "product-301"), idsOf(response));
    }

    @Test
    @DisplayName("결과가 많아도 몰 하나가 100건을 넘겨 주지 않는다")
    void search_manyMatches_isCappedPerMall() {
        // given: 상한이 없으면 빈 검색어 한 번에 카탈로그 전량이 나간다
        CatalogService bulk = serviceOf(CatalogFixture.many(120));

        // when
        CatalogMessages.ItemsResponse byKeyword =
                bulk.search("merchant-a", new CatalogMessages.SearchRequest("대량", null));
        CatalogMessages.ItemsResponse byBlank =
                bulk.search("merchant-a", new CatalogMessages.SearchRequest("", null));

        // then
        assertEquals(100, byKeyword.items().size());
        assertEquals(100, byBlank.items().size());
    }
}
