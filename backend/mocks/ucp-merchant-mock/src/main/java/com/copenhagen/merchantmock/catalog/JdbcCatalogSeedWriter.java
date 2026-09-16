package com.copenhagen.merchantmock.catalog;

import java.util.ArrayList;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 시드를 products 에 upsert 한다. JdbcTemplate 만 쓰고 JPA 를 쓰지 않는다(DESIGN.md 4절).
 *
 * <p>external_id 충돌에 덮어쓰기로 간다. 시드 생성기가 id 를 external_id 에서 결정적으로 만들기
 * 때문에 다시 돌려도 같은 행을 갱신한다(5.7절). 기동할 때마다 행이 늘지 않는다.
 *
 * <p>기존 상품을 지우지 않는다. V3 fixture 5건은 그대로 남는다. 시드 파일을 바꿔 끼우는 것은
 * 몰마다 DB 가 따로 있는 전제이고, 한 DB 에 두 몰을 겹쳐 넣는 것은 지원하지 않는다.
 */
@Repository
public class JdbcCatalogSeedWriter implements CatalogSeedWriter {

    private static final String UPSERT = """
            INSERT INTO products (id, external_id, title, image_url, base_price, currency, status, variants_json)
                 VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb)
            ON CONFLICT (external_id) DO UPDATE
                    SET title         = EXCLUDED.title,
                        image_url     = EXCLUDED.image_url,
                        base_price    = EXCLUDED.base_price,
                        currency      = EXCLUDED.currency,
                        status        = EXCLUDED.status,
                        variants_json = EXCLUDED.variants_json,
                        updated_at    = now()
            """;

    private final JdbcTemplate jdbcTemplate;

    public JdbcCatalogSeedWriter(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public int upsertAll(List<CatalogSeed.Entry> entries) {
        if (entries.isEmpty()) {
            return 0;
        }
        List<Object[]> batch = new ArrayList<>(entries.size());
        for (CatalogSeed.Entry entry : entries) {
            batch.add(new Object[] {
                    entry.id(),
                    entry.externalId(),
                    entry.title(),
                    entry.imageUrl(),
                    entry.basePrice(),
                    entry.currency(),
                    entry.status(),
                    entry.variantsJson(),
            });
        }
        int[] written = jdbcTemplate.batchUpdate(UPSERT, batch);
        return written.length;
    }
}
