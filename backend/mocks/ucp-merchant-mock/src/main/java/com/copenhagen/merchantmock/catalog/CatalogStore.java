package com.copenhagen.merchantmock.catalog;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 카탈로그 인메모리 스냅샷. 몰마다 색인이 따로다(393, DESIGN.md 5.4절).
 *
 * <p>기동 시 전량 올린다. 몰 셋 합쳐 수천 건 규모에서 매 요청 DB 조회보다 단순하다(DESIGN.md 13절).
 * 같은 상품이 몰마다 한 행씩 있고 externalId 와 SKU 문자열이 몰 사이에 겹치므로 색인은 몰 아래에 둔다.
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
        Snapshot next = Snapshot.of(products);
        snapshot.set(next);
        LOG.info("카탈로그 로딩 완료. 몰 {}개, 상품 {}건, SKU {}건", next.malls().size(), products.size(), next.skuCount());
    }

    /** 몰 하나의 상품 전부. 모르는 몰이면 빈 목록이다 — 몰 존재 검사는 서비스가 먼저 한다. */
    public List<Product> all(String merchantId) {
        return snapshot.get().mall(merchantId).ordered();
    }

    public Optional<Product> byExternalId(String merchantId, String externalId) {
        return Optional.ofNullable(snapshot.get().mall(merchantId).byExternalId().get(externalId));
    }

    public Optional<Product.Variant> bySku(String merchantId, String sku) {
        return Optional.ofNullable(snapshot.get().mall(merchantId).bySku().get(sku));
    }

    /** 몰 전부 합친 상품 수. 로그와 테스트용이다. */
    public int size() {
        return snapshot.get().malls().values().stream().mapToInt(index -> index.ordered().size()).sum();
    }

    private record Snapshot(Map<String, MallIndex> malls) {

        static Snapshot empty() {
            return new Snapshot(Map.of());
        }

        static Snapshot of(List<Product> products) {
            Map<String, List<Product>> grouped = new LinkedHashMap<>();
            for (Product product : products) {
                grouped.computeIfAbsent(product.merchantId(), key -> new ArrayList<>()).add(product);
            }
            Map<String, MallIndex> malls = new LinkedHashMap<>();
            grouped.forEach((merchantId, list) -> malls.put(merchantId, MallIndex.of(list)));
            return new Snapshot(Map.copyOf(malls));
        }

        MallIndex mall(String merchantId) {
            return malls.getOrDefault(merchantId, MallIndex.EMPTY);
        }

        int skuCount() {
            return malls.values().stream().mapToInt(index -> index.bySku().size()).sum();
        }
    }

    private record MallIndex(
            List<Product> ordered,
            Map<String, Product> byExternalId,
            Map<String, Product.Variant> bySku) {

        static final MallIndex EMPTY = new MallIndex(List.of(), Map.of(), Map.of());

        static MallIndex of(List<Product> products) {
            Map<String, Product> byExternalId = new LinkedHashMap<>();
            Map<String, Product.Variant> bySku = new LinkedHashMap<>();
            for (Product product : products) {
                byExternalId.put(product.externalId(), product);
                for (Product.Variant variant : product.variants()) {
                    bySku.put(variant.sku(), variant);
                }
            }
            return new MallIndex(List.copyOf(products), Map.copyOf(byExternalId), Map.copyOf(bySku));
        }
    }
}
