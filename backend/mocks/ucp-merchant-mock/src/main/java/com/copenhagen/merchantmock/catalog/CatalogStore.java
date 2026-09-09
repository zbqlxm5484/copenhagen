package com.copenhagen.merchantmock.catalog;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 카탈로그 인메모리 스냅샷.
 *
 * <p>기동 시 전량 올린다. 상품 수십 개 규모에서 매 요청 DB 조회보다 단순하다(DESIGN.md 13절).
 *
 * <p>스냅샷을 통째로 갈아 끼우는 방식이다. 읽는 쪽은 불변 Map 하나를 보므로 잠금이 필요 없다.
 * 시나리오 API 로 재고를 바꾸면 {@link #reload()} 로 새 스냅샷을 만든다(Jira 185).
 */
@Component
public class CatalogStore {

    private static final Logger LOG = LoggerFactory.getLogger(CatalogStore.class);

    private final ProductRepository repository;
    private final AtomicReference<Snapshot> snapshot = new AtomicReference<>(Snapshot.empty());

    public CatalogStore(ProductRepository repository) {
        this.repository = repository;
    }

    /** 기동 직후 한 번. DataSource 준비가 끝난 뒤 불려야 하므로 생성자에서 하지 않는다. */
    public void load() {
        reload();
    }

    public void reload() {
        List<Product> products = repository.findAll();
        snapshot.set(Snapshot.of(products));
        LOG.info("카탈로그 로딩 완료. 상품 {}건, SKU {}건", products.size(), snapshot.get().bySku().size());
    }

    public List<Product> all() {
        return snapshot.get().ordered();
    }

    public Optional<Product> byExternalId(String externalId) {
        return Optional.ofNullable(snapshot.get().byExternalId().get(externalId));
    }

    public Optional<Product.Variant> bySku(String sku) {
        return Optional.ofNullable(snapshot.get().bySku().get(sku));
    }

    private record Snapshot(
            List<Product> ordered,
            Map<String, Product> byExternalId,
            Map<String, Product.Variant> bySku) {

        static Snapshot empty() {
            return new Snapshot(List.of(), Map.of(), Map.of());
        }

        static Snapshot of(List<Product> products) {
            Map<String, Product> byExternalId = new LinkedHashMap<>();
            Map<String, Product.Variant> bySku = new LinkedHashMap<>();
            for (Product product : products) {
                byExternalId.put(product.externalId(), product);
                for (Product.Variant variant : product.variants()) {
                    bySku.put(variant.sku(), variant);
                }
            }
            return new Snapshot(List.copyOf(products), Map.copyOf(byExternalId), Map.copyOf(bySku));
        }
    }
}
