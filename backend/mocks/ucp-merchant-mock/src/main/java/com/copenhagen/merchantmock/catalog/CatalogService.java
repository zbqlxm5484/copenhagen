package com.copenhagen.merchantmock.catalog;

import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import com.copenhagen.merchantmock.support.ApiException;
import com.copenhagen.merchantmock.support.MerchantProperties;
import org.springframework.stereotype.Service;

/**
 * Catalog Search·Lookup·Product. 몰 하나 안에서만 찾는다(393).
 *
 * <p>여기서 주는 가격과 재고는 탐색용 현재 정보다. 최종 거래 조건의 원본은 Checkout 이다.
 * Agent 가 이 값을 그대로 믿고 결제를 시도해도 Core 가 Checkout 값으로 다시 확정한다(DESIGN.md 1절).
 *
 * <p>DISCONTINUED 상품은 세 엔드포인트 모두에서 숨긴다. 파는 물건이 아니다.
 */
@Service
public class CatalogService {

    /**
     * 몰 하나가 검색 한 번에 돌려줄 수 있는 최대 건수(470). Core 는 모든 몰의 결과를 합친 뒤 자르므로
     * 몰당 상한이다. MCP 도구의 {@code pageSize} 상한이 30 이라 페이지를 여러 장 넘겨도 걸리지 않는다.
     * 상한이 없으면 빈 검색어 한 번에 카탈로그 전량이 나간다.
     */
    private static final int MAX_RESULTS = 100;

    /** 낱말 경계. 한글·영문·숫자가 아닌 것은 전부 구분자로 본다. 상품명의 대괄호·슬래시·괄호를 가른다. */
    private static final Pattern WORD_BREAK = Pattern.compile("[^0-9a-z\uac00-\ud7a3]+");

    private final CatalogStore store;
    private final MerchantProperties merchants;
    private final Map<String, List<String>> synonyms;

    public CatalogService(CatalogStore store, MerchantProperties merchants) {
        this.store = store;
        this.merchants = merchants;
        this.synonyms = indexSynonyms(merchants.catalog().synonyms());
    }

    /** 낱말 하나에서 자기 묶음으로 가는 색인. 같은 낱말이 두 묶음에 있으면 먼저 온 묶음을 쓴다. */
    private static Map<String, List<String>> indexSynonyms(List<List<String>> groups) {
        Map<String, List<String>> index = new HashMap<>();
        for (List<String> group : groups) {
            for (String word : group) {
                index.putIfAbsent(word, group);
            }
        }
        return Map.copyOf(index);
    }

    /**
     * 검색어를 공백으로 쪼개 모든 토큰이 제목·브랜드·카테고리 중 어딘가에 있으면 통과시키고,
     * 어디서 맞았는지로 줄을 세운다(470).
     *
     * <p>순서가 곧 결과다. MCP 도구 기본값이 {@code pageSize} 4 · {@code sort} RELEVANCE 인데 Core 는
     * RELEVANCE 를 무정렬로 통과시키므로 여기서 매긴 순서가 그대로 사용자 화면이 된다.
     *
     * <p>카테고리 매칭은 재현율을 올리는 대신 정밀도를 떨어뜨린다 — "올리브유" 에 같은 카테고리의
     * 고추장이 딸려 온다. 그래서 제목에서 맞은 것을 앞에 두고 카테고리로만 맞은 것을 뒤로 민다.
     */
    public CatalogMessages.ItemsResponse search(String merchantId, CatalogMessages.SearchRequest request) {
        merchants.requireMall(merchantId);
        List<List<String>> groups = expand(request == null ? null : request.query());
        CatalogMessages.SearchRequest.Filters filters = request == null ? null : request.filters();
        List<CatalogMessages.Item> items = store.all(merchantId).stream()
                .filter(CatalogService::onSale)
                .filter(product -> matches(product, groups))
                .filter(product -> matchesPrice(product, filters))
                .filter(product -> matchesCurrency(product, filters))
                .sorted(Comparator.comparingInt((Product product) -> relevance(product, groups)).reversed())
                .limit(MAX_RESULTS)
                .map(CatalogMessages.Item::of)
                .toList();
        return new CatalogMessages.ItemsResponse(items);
    }

    /** 없는 ID 는 조용히 빠진다. 404 를 내지 않는다. Core 가 개수를 비교해 판단한다. */
    public CatalogMessages.ItemsResponse lookup(String merchantId, CatalogMessages.LookupRequest request) {
        merchants.requireMall(merchantId);
        if (request == null || request.productIds() == null || request.productIds().isEmpty()) {
            return new CatalogMessages.ItemsResponse(List.of());
        }
        List<CatalogMessages.Item> items = request.productIds().stream()
                .map(productId -> store.byExternalId(merchantId, productId))
                .flatMap(java.util.Optional::stream)
                .filter(CatalogService::onSale)
                .map(CatalogMessages.Item::of)
                .toList();
        return new CatalogMessages.ItemsResponse(items);
    }

    public CatalogMessages.ProductDetail product(String merchantId, CatalogMessages.ProductRequest request) {
        merchants.requireMall(merchantId);
        String productId = request == null ? null : request.productId();
        if (productId == null || productId.isBlank()) {
            throw ApiException.productNotFound("(빈 productId)");
        }
        return store.byExternalId(merchantId, productId)
                .filter(CatalogService::onSale)
                .map(CatalogMessages.ProductDetail::of)
                .orElseThrow(() -> ApiException.productNotFound(productId));
    }

    private static boolean onSale(Product product) {
        return product.status() == Product.ProductStatus.ACTIVE;
    }

    /**
     * 검색어를 낱말로 쪼개고 낱말마다 동의어 묶음으로 넓힌다. 묶음이 없는 낱말은 혼자 있는 묶음이 된다.
     *
     * <p>빈 검색어는 묶음이 없어 전부 통과한다. 그때 결과를 막는 것은 {@link #MAX_RESULTS} 하나뿐이다.
     */
    private List<List<String>> expand(String query) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        return Arrays.stream(query.trim().toLowerCase(Locale.ROOT).split("\\s+"))
                .filter(token -> !token.isEmpty())
                .map(token -> synonyms.getOrDefault(token, List.of(token)))
                .toList();
    }

    /** 묶음마다 하나라도 제목·브랜드·카테고리에 있으면 통과다. 묶음 사이는 AND 다. */
    private static boolean matches(Product product, List<List<String>> groups) {
        String haystack = lower(product.title()) + ' ' + lower(product.brand()) + ' ' + lower(product.category());
        return groups.stream().allMatch(group -> anyIn(group, haystack));
    }

    /**
     * 큰 값이 앞이다. 빈 검색어면 전부 0 이라 시드 순서가 그대로 남는다.
     *
     * <p>단계를 넷으로 둔 이유는 실측이다. 제목 부분문자열만 보면 "우유" 검색에서 우유팩 키링과
     * 우유니 캔들이 실제 우유와 같은 취급을 받는다. 낱말 단위로 맞은 것을 한 칸 올리면 그 둘이
     * 아래로 내려간다. 카테고리로만 맞은 것은 맨 뒤다 — 재현율은 얻되 첫 화면은 내주지 않는다.
     *
     * <p>{@code List.sort} 가 안정 정렬이므로 같은 단계끼리는 시드 순서를 지킨다. 같은 요청에 같은 답이
     * 나와야 시연과 테스트가 성립한다.
     */
    private static int relevance(Product product, List<List<String>> groups) {
        if (groups.isEmpty()) {
            return 0;
        }
        String title = lower(product.title());
        List<String> titleWords = List.of(WORD_BREAK.split(title));
        if (groups.stream().allMatch(group -> group.stream().anyMatch(titleWords::contains))) {
            return 4;
        }
        if (groups.stream().allMatch(group -> anyIn(group, title))) {
            return 3;
        }
        String brand = lower(product.brand());
        if (groups.stream().allMatch(group -> anyIn(group, title) || anyIn(group, brand))) {
            return 2;
        }
        return 1;
    }

    private static boolean anyIn(List<String> group, String haystack) {
        return group.stream().anyMatch(haystack::contains);
    }

    private static String lower(String field) {
        return field == null ? "" : field.toLowerCase(Locale.ROOT);
    }

    private static boolean matchesPrice(Product product, CatalogMessages.SearchRequest.Filters filters) {
        if (filters == null || filters.maxPrice() == null) {
            return true;
        }
        return product.displayPrice() <= filters.maxPrice();
    }

    private static boolean matchesCurrency(Product product, CatalogMessages.SearchRequest.Filters filters) {
        if (filters == null || filters.currency() == null || filters.currency().isBlank()) {
            return true;
        }
        return product.currency().equalsIgnoreCase(filters.currency());
    }
}
