package com.copenhagen.merchantmock.catalog;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 기동이 끝난 뒤 카탈로그를 메모리에 올린다.
 *
 * <p>Flyway 마이그레이션이 먼저 끝나야 하므로 생성자나 {@code @PostConstruct} 가 아니라
 * ApplicationReadyEvent 에서 부른다. V3 시드가 들어간 뒤여야 상품이 보인다.
 */
@Component
public class CatalogWarmUp {

    private final CatalogStore store;

    public CatalogWarmUp(CatalogStore store) {
        this.store = store;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void loadCatalog() {
        store.load();
    }
}
