package com.copenhagen.merchantmock.catalog;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 기동이 끝난 뒤 카탈로그를 메모리에 올린다.
 *
 * <p>Flyway 마이그레이션이 먼저 끝나야 하므로 생성자나 {@code @PostConstruct} 가 아니라
 * ApplicationReadyEvent 에서 부른다. V3 시드가 들어간 뒤여야 상품이 보인다.
 *
 * <p>시연 카탈로그 시드를 넣고 그다음 스냅샷을 올린다. 둘의 순서를 이벤트 리스너 우선순위에
 * 맡기지 않고 한 메서드 안에서 고정한다(DESIGN.md 5.7절). 순서가 뒤집히면 시드가 들어간 뒤에도
 * 스냅샷에는 안 보인다.
 */
@Component
public class CatalogWarmUp {

    private final CatalogSeedLoader seedLoader;
    private final CatalogStore store;

    public CatalogWarmUp(CatalogSeedLoader seedLoader, CatalogStore store) {
        this.seedLoader = seedLoader;
        this.store = store;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void loadCatalog() {
        seedLoader.loadIfConfigured();
        store.load();
    }
}
