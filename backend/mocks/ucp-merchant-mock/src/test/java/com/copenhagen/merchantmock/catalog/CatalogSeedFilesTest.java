package com.copenhagen.merchantmock.catalog;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.copenhagen.merchantmock.support.MerchantPropertiesFixture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.DefaultResourceLoader;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 커밋한 시연 카탈로그 시드 셋을 검사한다(DESIGN.md 5.7절, ADR 0007).
 *
 * <p>{@code tools/build_demo_catalog.py} 가 만든 산출물이 DB 제약과 이 모듈의 전제를 지키는지
 * 본다. 데이터를 갈아끼울 때 이 테스트가 먼저 잡아야 한다. 생성기를 고치면 여기가 따라온다.
 *
 * <p>몰 셋은 원본 1,408건 풀을 나눠 갖고, 같은 상품은 몰이 달라도 같은 productId 에 다른 가격이다.
 * 그 전제가 깨지면 가격 비교 시연이 성립하지 않으므로 여기서 잡는다.
 */
class CatalogSeedFilesTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final List<String> MALLS = List.of("merchant-a", "merchant-b", "merchant-c");
    /** 원본 korean_products_clean.sqlite 의 clean_catalog 건수. 생성기가 풀 전량을 쓴다. */
    private static final int POOL_SIZE = 1408;
    /** 생성기의 CARRY_RATIO 0.78 × 1,408 ≈ 1,098. 해시 편차를 넉넉히 감안한 하한이다. */
    private static final int MIN_PRODUCTS_PER_MALL = 1000;

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
    @ValueSource(strings = {"merchant-a", "merchant-b", "merchant-c"})
    @DisplayName("몰마다 상품이 천 건 이상이고 external_id 가 겹치지 않는다")
    void seedFile_hasEnoughProductsWithUniqueExternalIds(String mall) {
        // given & when
        List<CatalogSeed.Entry> entries = load(mall);

        // then
        assertTrue(entries.size() >= MIN_PRODUCTS_PER_MALL, mall + " 상품 " + entries.size() + "건");
        assertTrue(entries.size() <= POOL_SIZE, mall + " 상품 " + entries.size() + "건");
        Set<String> externalIds = new HashSet<>();
        entries.forEach(entry -> assertTrue(externalIds.add(entry.externalId()), entry.externalId()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"merchant-a", "merchant-b", "merchant-c"})
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
    @ValueSource(strings = {"merchant-a", "merchant-b", "merchant-c"})
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
    @ValueSource(strings = {"merchant-a", "merchant-b", "merchant-c"})
    @DisplayName("살 수 있는 상품이 대부분이다")
    void seedFile_mostProductsArePurchasable(String mall) {
        // given
        List<CatalogSeed.Entry> entries = load(mall);

        // when
        long purchasable = entries.stream().filter(CatalogSeedFilesTest::hasStock).count();

        // then
        // 몰별 품절 해시 5% 에 원본 availability 의 품절이 얹혀 10% 안팎이 정상이다.
        // 그보다 훨씬 많으면 시연에서 살 물건이 없다는 뜻이므로 85% 에 선을 그어 둔다.
        assertTrue(purchasable * 100 >= entries.size() * 85L,
                mall + " 구매 가능 " + purchasable + " / " + entries.size());
    }

    @Test
    @DisplayName("풀 1,408건이 전부 최소 한 몰에 있다")
    void seedFiles_coverWholePool() {
        // given & when
        Set<String> union = new HashSet<>();
        for (String mall : MALLS) {
            load(mall).forEach(entry -> union.add(entry.externalId()));
        }

        // then
        assertEquals(POOL_SIZE, union.size());
    }

    @Test
    @DisplayName("같은 상품은 몰이 달라도 productId 가 같고 가격은 원본의 ±10% 안에서 갈린다")
    void seedFiles_sameProductSharesIdAndPricesDiffer() {
        // given
        Map<String, List<CatalogSeed.Entry>> byExternalId = new HashMap<>();
        for (String mall : MALLS) {
            for (CatalogSeed.Entry entry : load(mall)) {
                byExternalId.computeIfAbsent(entry.externalId(), key -> new ArrayList<>()).add(entry);
            }
        }

        // when
        int shared = 0;
        int differing = 0;
        for (List<CatalogSeed.Entry> offers : byExternalId.values()) {
            if (offers.size() < 2) {
                continue;
            }
            shared++;
            long min = offers.stream().mapToLong(CatalogSeed.Entry::basePrice).min().orElseThrow();
            long max = offers.stream().mapToLong(CatalogSeed.Entry::basePrice).max().orElseThrow();
            for (CatalogSeed.Entry offer : offers) {
                assertEquals(offers.get(0).id(), offer.id(), offer.externalId());
            }
            // 두 몰이 각각 0.90 과 1.10 을 받았을 때가 가장 벌어진다. 반올림 한 단위(100원)를 여유로 둔다.
            assertTrue(max * 90 <= min * 110 + 100 * 110, offers.get(0).externalId() + " " + min + ".." + max);
            if (min != max) {
                differing++;
            }
        }

        // then
        // 취급 확률 0.78 이면 두 몰 이상에 있는 상품이 풀의 85% 안팎이다. 그중 가격이 같은 것은 반올림이 겹친 소수다.
        assertTrue(shared >= POOL_SIZE * 7 / 10, "두 몰 이상에 있는 상품 " + shared + "건");
        assertTrue(differing * 100 >= shared * 90L, "가격이 갈리는 상품 " + differing + " / " + shared);
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
