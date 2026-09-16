package com.copenhagen.merchantmock.catalog;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.copenhagen.merchantmock.support.MerchantPropertiesFixture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.DefaultResourceLoader;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 커밋한 시연 카탈로그 시드 셋을 검사한다(DESIGN.md 5.7절).
 *
 * <p>{@code tools/build_demo_catalog.py} 가 만든 산출물이 DB 제약과 이 모듈의 전제를 지키는지
 * 본다. 데이터를 갈아끼울 때 이 테스트가 먼저 잡아야 한다. 생성기를 고치면 여기가 따라온다.
 */
class CatalogSeedFilesTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static List<CatalogSeed.Entry> load(String mall) {
        List<CatalogSeed.Entry> captured = new ArrayList<>();
        CatalogSeedLoader loader = new CatalogSeedLoader(
                MerchantPropertiesFixture.withCatalogSeedFile("classpath:catalog/" + mall + ".json"),
                new DefaultResourceLoader(),
                MAPPER,
                entries -> {
                    captured.addAll(entries);
                    return entries.size();
                });
        loader.loadIfConfigured();
        return captured;
    }

    @ParameterizedTest
    @ValueSource(strings = {"kurly", "danawa", "daiso"})
    @DisplayName("몰마다 상품 100건이고 external_id 가 겹치지 않는다")
    void seedFile_hasHundredProductsWithUniqueExternalIds(String mall) {
        // given & when
        List<CatalogSeed.Entry> entries = load(mall);

        // then
        assertEquals(100, entries.size());
        Set<String> externalIds = new HashSet<>();
        entries.forEach(entry -> assertTrue(externalIds.add(entry.externalId()), entry.externalId()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"kurly", "danawa", "daiso"})
    @DisplayName("컬럼 길이와 금액이 스키마 제약 안에 있다")
    void seedFile_fitsSchemaConstraints(String mall) {
        // given & when
        List<CatalogSeed.Entry> entries = load(mall);

        // then
        for (CatalogSeed.Entry entry : entries) {
            assertTrue(entry.externalId().length() <= 100, entry.externalId());
            assertTrue(entry.title().length() <= 255, entry.title());
            assertTrue(entry.imageUrl().length() <= 500, entry.imageUrl());
            assertEquals("KRW", entry.currency());
            assertEquals("ACTIVE", entry.status());
            assertTrue(entry.basePrice() > 0, entry.externalId());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"kurly", "danawa", "daiso"})
    @DisplayName("SKU 가 파일 안에서 유일하고 옵션 최종가가 0 보다 크다")
    void seedFile_hasUniqueSkusAndPositiveOptionPrices(String mall) {
        // given
        List<CatalogSeed.Entry> entries = load(mall);
        Set<String> skus = new HashSet<>();

        // when & then
        for (CatalogSeed.Entry entry : entries) {
            JsonNode variants = MAPPER.readTree(entry.variantsJson());
            assertFalse(variants.isEmpty(), entry.externalId());
            assertTrue(variants.size() <= 10, entry.externalId() + " 옵션 " + variants.size() + "개");
            for (JsonNode variant : variants) {
                String sku = variant.path("sku").asString();
                assertTrue(skus.add(sku), "SKU 중복: " + sku);
                // priceDelta 는 절대가가 아니라 차액이다. base + delta 가 최종가다.
                assertTrue(entry.basePrice() + variant.path("priceDelta").asLong() > 0, sku);
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"kurly", "danawa", "daiso"})
    @DisplayName("살 수 있는 상품이 대부분이다")
    void seedFile_mostProductsArePurchasable(String mall) {
        // given
        List<CatalogSeed.Entry> entries = load(mall);

        // when
        long purchasable = entries.stream().filter(CatalogSeedFilesTest::hasStock).count();

        // then
        // 전 옵션 품절은 원본 availability 를 그대로 옮긴 결과라 몇 건 있는 것이 정상이다.
        // 다만 절반을 넘으면 시연에서 살 물건이 없다는 뜻이므로 선을 그어 둔다.
        assertTrue(purchasable >= 80, mall + " 구매 가능 " + purchasable + "건");
    }

    private static boolean hasStock(CatalogSeed.Entry entry) {
        for (JsonNode variant : MAPPER.readTree(entry.variantsJson())) {
            if (variant.path("stock").asInt() > 0) {
                return true;
            }
        }
        return false;
    }
}
