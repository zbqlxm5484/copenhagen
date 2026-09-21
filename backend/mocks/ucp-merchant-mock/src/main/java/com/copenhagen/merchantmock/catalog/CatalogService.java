package com.copenhagen.merchantmock.catalog;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.IntStream;

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

    private final CatalogStore store;
    private final MerchantProperties merchants;
    private final Map<String, List<String>> synonyms;
    private final MerchantProperties.Catalog.Search tuning;

    public CatalogService(CatalogStore store, MerchantProperties merchants) {
        this.store = store;
        this.merchants = merchants;
        this.synonyms = indexSynonyms(merchants.catalog().synonyms());
        this.tuning = merchants.catalog().search();
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
     * 검색어를 낱말로 쪼개 동의어로 넓히고, 하나라도 제목·브랜드·카테고리에 있으면 후보로 삼아 점수로
     * 줄을 세운다(DESIGN.md 6.7절, ADR 0012).
     *
     * <p>470 은 모든 낱말이 맞아야 통과시켰다(AND). 수식어가 하나 붙으면 0건이라 "가벼운 러닝화", "러닝화
     * 추천해줘" 가 전부 0 이었다. OR 로 바꾸면 결과 수의 책임이 순서로 옮겨 온다 — MCP 도구 기본값이
     * {@code pageSize} 4 라 첫 화면 넷이 곧 결과다.
     *
     * <pre>
     * 점수 = Σ_{맞은 묶음 g} f(g) × t(g)
     *   f  수식어 감쇠. 검색어 낱말이 fluff-words 에 있으면 fluff-weight(0.2). 전부 수식어면 감쇠하지 않는다
     *   t  끝 낱말 가중. 검색어의 마지막 낱말이면 tail-boost(1.5). 한국어 명사구는 핵심 명사가 뒤에 온다
     * 동점은 자리 단계(제목 낱말 4 > 제목 부분 3 > 브랜드 2 > 카테고리 1, 맞은 묶음 중 가장 약한 자리),
     * 그래도 같으면 풀 순서. 안정 정렬이라 같은 요청에 같은 답이 나온다.
     * </pre>
     *
     * <p>흔한 낱말을 가볍게 보는 가중치(역문서빈도)는 넣었다가 뺐다. 1,592건에서는 적중이 +5 였지만 10만 건 풀에서는
     * -4 였다 — "남자 양말" 에서 양말이 805건으로 흔해 가중치가 0 에 가까워지고 "남자" 만 맞은 한복·스킨이 위로 왔다.
     * 핵심 명사가 흔한 낱말인 경우가 많아 방향이 틀린 신호였다(DESIGN.md 6.7절 실측).
     *
     * <p>두 번 훑는다. 첫 번째로 상품마다 묶음이 어느 자리에서 맞았는지(병렬), 두 번째로 점수. 10만 건 풀에서 요청당
     * 수십 ms 를 목표로 하고 실측은 13절에 적는다.
     */
    public CatalogMessages.ItemsResponse search(String merchantId, CatalogMessages.SearchRequest request) {
        merchants.requireMall(merchantId);
        CatalogMessages.SearchRequest.Filters filters = request == null ? null : request.filters();
        List<Product> products = store.all(merchantId);
        List<Term> terms = expand(request == null ? null : request.query());

        List<CatalogMessages.Item> items = new ArrayList<>();
        if (terms.isEmpty()) {
            // 빈 검색어는 전부 후보다. 순서를 매길 근거가 없어 풀 순서 그대로 상한까지 낸다(470 결정 유지).
            for (Product product : products) {
                if (items.size() == MAX_RESULTS) {
                    break;
                }
                if (onSale(product) && matchesPrice(product, filters) && matchesCurrency(product, filters)) {
                    items.add(CatalogMessages.Item.of(product));
                }
            }
            return new CatalogMessages.ItemsResponse(items);
        }

        List<SearchDoc> docs = store.searchDocs(merchantId);
        int width = terms.size();
        byte[] tiers = new byte[products.size() * width];
        // 1차 훑기는 상품마다 독립이라 병렬로 돈다. 10만 건 × 낱말 × 세 필드 contains 가 검색 시간의 대부분이다.
        // 쓰는 칸이 상품마다 다르므로 배열에 경쟁이 없다. df 는 2차에서 순서대로 센다.
        IntStream.range(0, products.size()).parallel().forEach(index -> {
            if (!onSale(products.get(index))) {
                return;
            }
            SearchDoc doc = docs.get(index);
            for (int k = 0; k < width; k++) {
                tiers[index * width + k] = doc.tier(terms.get(k).group());
            }
        });
        double[] weights = weights(terms);

        List<Candidate> candidates = new ArrayList<>();
        for (int index = 0; index < products.size(); index++) {
            Product product = products.get(index);
            if (!onSale(product)) {
                continue;
            }
            double score = 0;
            int weakest = Integer.MAX_VALUE;
            for (int k = 0; k < width; k++) {
                byte tier = tiers[index * width + k];
                if (tier > 0) {
                    score += weights[k];
                    weakest = Math.min(weakest, tier);
                }
            }
            if (weakest == Integer.MAX_VALUE
                    || !matchesPrice(product, filters) || !matchesCurrency(product, filters)) {
                continue;
            }
            candidates.add(new Candidate(index, score, weakest));
        }
        candidates.sort(Comparator.comparingDouble(Candidate::score).reversed()
                .thenComparing(Comparator.comparingInt(Candidate::weakestTier).reversed())
                .thenComparingInt(Candidate::index));
        for (Candidate candidate : candidates) {
            if (items.size() == MAX_RESULTS) {
                break;
            }
            items.add(CatalogMessages.Item.of(products.get(candidate.index())));
        }
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

    /** 검색어 낱말 하나와 그 동의어 묶음. 수식어 판정은 원래 낱말로 한다 — 묶음의 다른 낱말이 아니라. */
    private record Term(String token, List<String> group) {
    }

    private record Candidate(int index, double score, int weakestTier) {
    }

    /**
     * 검색어를 낱말로 쪼개고 낱말마다 동의어 묶음으로 넓힌다. 묶음이 없는 낱말은 혼자 있는 묶음이 된다.
     * 빈 검색어는 빈 목록이고 그때 결과를 막는 것은 {@link #MAX_RESULTS} 하나뿐이다.
     */
    private List<Term> expand(String query) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        return Arrays.stream(query.trim().toLowerCase(Locale.ROOT).split("\\s+"))
                .filter(token -> !token.isEmpty())
                .map(token -> new Term(token, synonyms.getOrDefault(token, List.of(token))))
                .toList();
    }

    /**
     * 묶음마다 가중치. 기본 1, 수식어는 가볍게, 마지막 낱말은 무겁게(6.7절).
     *
     * <p>검색어 낱말이 전부 수식어면 감쇠하지 않는다 — 그때는 그것이 검색어다. 낱말이 하나면 끝 낱말 가중도
     * 뜻이 없어 곱하지 않는다.
     */
    private double[] weights(List<Term> terms) {
        boolean allFluff = terms.stream().allMatch(term -> tuning.isFluff(term.token()));
        double[] weights = new double[terms.size()];
        for (int k = 0; k < terms.size(); k++) {
            double weight = 1.0;
            if (!allFluff && tuning.isFluff(terms.get(k).token())) {
                weight *= tuning.fluffWeight();
            }
            if (terms.size() > 1 && k == terms.size() - 1) {
                weight *= tuning.tailBoost();
            }
            weights[k] = weight;
        }
        return weights;
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
