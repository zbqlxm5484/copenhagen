package com.copenhagen.merchantmock.catalog;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import com.copenhagen.merchantmock.support.MerchantProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 시연 카탈로그 시드를 기동 시 한 번 적재한다(DESIGN.md 5.7절).
 *
 * <p>{@code merchant.catalog.seed-file} 이 비어 있으면 아무 일도 하지 않는다. 기본값이 비어
 * 있으므로 테스트와 CI 는 V3 fixture 5건만 본다. 시연은 compose 에서 환경변수로 켠다.
 *
 * <p>경로가 설정돼 있는데 파일이 없으면 기동을 세운다. 시드가 조용히 안 들어가서 상품 5건만
 * 보이는 채로 시연에 들어가는 것이 더 나쁘다.
 */
@Component
public class CatalogSeedLoader {

    private static final Logger LOG = LoggerFactory.getLogger(CatalogSeedLoader.class);

    private final MerchantProperties properties;
    private final ResourceLoader resourceLoader;
    private final ObjectMapper objectMapper;
    private final CatalogSeedWriter writer;

    public CatalogSeedLoader(
            MerchantProperties properties,
            ResourceLoader resourceLoader,
            ObjectMapper objectMapper,
            CatalogSeedWriter writer) {
        this.properties = properties;
        this.resourceLoader = resourceLoader;
        this.objectMapper = objectMapper;
        this.writer = writer;
    }

    /** 설정돼 있으면 시드를 넣고 쓴 건수를, 아니면 0 을 돌려준다. */
    public int loadIfConfigured() {
        MerchantProperties.Catalog catalog = properties.catalog();
        if (catalog == null || !catalog.hasSeedFile()) {
            LOG.info("시연 카탈로그 시드 없음. merchant.catalog.seed-file 이 비어 있다");
            return 0;
        }

        String location = catalog.seedFile();
        CatalogSeed seed = read(location);
        int written = writer.upsertAll(seed.products());
        LOG.info("시연 카탈로그 시드 적재 완료. 몰 {}({}), 상품 {}건, 출처 {}",
                seed.mallName(), seed.mall(), written, location);
        return written;
    }

    private CatalogSeed read(String location) {
        Resource resource = resourceLoader.getResource(location);
        if (!resource.exists()) {
            throw new IllegalStateException(
                    "merchant.catalog.seed-file 이 가리키는 파일이 없다: " + location);
        }
        try (InputStream input = resource.getInputStream()) {
            return parse(objectMapper.readTree(input), location);
        } catch (IOException | JacksonException exception) {
            // IOException 은 파일을 못 읽은 것, JacksonException 은 JSON 형식이 깨진 것이다.
            throw new IllegalStateException("시연 카탈로그 시드를 읽을 수 없다: " + location, exception);
        }
    }

    private CatalogSeed parse(JsonNode root, String location) {
        JsonNode products = root.path("products");
        if (!products.isArray() || products.isEmpty()) {
            throw new IllegalStateException("시연 카탈로그 시드에 products 배열이 없다: " + location);
        }

        List<CatalogSeed.Entry> entries = new ArrayList<>(products.size());
        for (JsonNode product : products) {
            entries.add(toEntry(product, location));
        }
        return new CatalogSeed(
                root.path("mall").asString(""),
                root.path("mallName").asString(""),
                List.copyOf(entries));
    }

    private CatalogSeed.Entry toEntry(JsonNode product, String location) {
        String externalId = product.path("externalId").asString("");
        if (externalId.isBlank()) {
            throw new IllegalStateException("시연 카탈로그 시드에 externalId 가 없는 상품이 있다: " + location);
        }

        JsonNode variants = product.path("variants");
        if (!variants.isArray() || variants.isEmpty()) {
            // 옵션이 없으면 pickVariant 가 SKU 를 못 찾아 체크아웃이 성립하지 않는다(5.7절).
            throw new IllegalStateException("옵션 없는 상품이 시드에 있다: " + externalId + " (" + location + ")");
        }

        return new CatalogSeed.Entry(
                product.path("id").asString(),
                externalId,
                product.path("title").asString(),
                product.path("imageUrl").asString(null),
                product.path("basePrice").asLong(),
                product.path("currency").asString("KRW"),
                product.path("status").asString("ACTIVE"),
                variants.toString());
    }
}
