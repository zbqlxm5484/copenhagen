package com.copenhagen.merchantmock.catalog;

import java.util.ArrayList;
import java.util.List;

import com.copenhagen.merchantmock.support.MerchantProperties;
import com.copenhagen.merchantmock.support.MerchantPropertiesFixture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 시연 카탈로그 시드 로더 단위 테스트. DB 없이 쓰기 경계를 가짜로 바꿔 돌린다(DESIGN.md 5.7절). */
class CatalogSeedLoaderTest {

    private final List<CatalogSeed.Entry> written = new ArrayList<>();

    private CatalogSeedLoader loaderOf(String seedFile) {
        MerchantProperties properties = MerchantPropertiesFixture.withCatalogSeedFile(seedFile);
        return new CatalogSeedLoader(
                properties,
                new DefaultResourceLoader(),
                new ObjectMapper(),
                entries -> {
                    written.addAll(entries);
                    return entries.size();
                });
    }

    @Test
    @DisplayName("시드 경로가 비어 있으면 아무것도 쓰지 않는다")
    void loadIfConfigured_blankSeedFile_writesNothing() {
        // given
        CatalogSeedLoader loader = loaderOf("");

        // when
        int count = loader.loadIfConfigured();

        // then
        assertEquals(0, count);
        assertTrue(written.isEmpty());
    }

    @Test
    @DisplayName("시드 파일을 읽어 products 컬럼으로 매핑한다")
    void loadIfConfigured_validSeedFile_mapsProductColumns() {
        // given
        CatalogSeedLoader loader = loaderOf("classpath:catalog-seed/valid.json");

        // when
        int count = loader.loadIfConfigured();

        // then
        assertEquals(2, count);
        CatalogSeed.Entry first = written.get(0);
        assertEquals("11111111-1111-4111-8111-111111111111", first.id());
        assertEquals("kurly:1000000001", first.externalId());
        assertEquals("시드 상품 하나", first.title());
        assertEquals("https://cdn.example.test/seed-1.jpg", first.imageUrl());
        assertEquals(12000L, first.basePrice());
        assertEquals("KRW", first.currency());
        assertEquals("ACTIVE", first.status());
    }

    @Test
    @DisplayName("variants 는 배열 JSON 문자열 그대로 실린다")
    void loadIfConfigured_validSeedFile_keepsVariantsAsJsonArray() {
        // given
        CatalogSeedLoader loader = loaderOf("classpath:catalog-seed/valid.json");

        // when
        loader.loadIfConfigured();

        // then
        String variantsJson = written.get(0).variantsJson();
        assertTrue(variantsJson.startsWith("["), variantsJson);
        assertTrue(variantsJson.contains("kurly:1000000001-SKU1"), variantsJson);
        assertTrue(variantsJson.contains("\"priceDelta\""), variantsJson);
    }

    @Test
    @DisplayName("경로가 설정됐는데 파일이 없으면 기동을 세운다")
    void loadIfConfigured_missingFile_throws() {
        // given
        CatalogSeedLoader loader = loaderOf("classpath:catalog-seed/no-such-file.json");

        // when & then
        IllegalStateException exception =
                assertThrows(IllegalStateException.class, loader::loadIfConfigured);
        assertTrue(exception.getMessage().contains("no-such-file.json"), exception.getMessage());
    }

    @Test
    @DisplayName("products 배열이 없으면 거절한다")
    void loadIfConfigured_noProductsArray_throws() {
        // given
        CatalogSeedLoader loader = loaderOf("classpath:catalog-seed/empty.json");

        // when & then
        assertThrows(IllegalStateException.class, loader::loadIfConfigured);
    }

    @Test
    @DisplayName("옵션 없는 상품이 들어 있으면 거절한다")
    void loadIfConfigured_productWithoutVariants_throws() {
        // given
        CatalogSeedLoader loader = loaderOf("classpath:catalog-seed/no-variants.json");

        // when & then
        IllegalStateException exception =
                assertThrows(IllegalStateException.class, loader::loadIfConfigured);
        assertTrue(exception.getMessage().contains("kurly:1000000009"), exception.getMessage());
    }
}
