package com.copenhagen.merchantmock.catalog;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 상품 하나의 검색용 사전 계산(DESIGN.md 6.7절). 스냅샷을 만들 때 한 번 만들고 요청마다 다시 만들지 않는다.
 *
 * <p>풀이 9만 건이면 요청마다 9만 번 소문자 변환과 낱말 분리를 하는 것이 검색 시간의 대부분이다.
 * 제목·브랜드·카테고리의 소문자와 제목 낱말 집합을 여기 둔다.
 *
 * @param title      소문자 제목
 * @param brand      소문자 브랜드. 없으면 빈 문자열
 * @param category   소문자 카테고리 경로. 없으면 빈 문자열
 * @param titleWords 제목을 낱말 경계로 쪼갠 집합. 낱말 단위 일치(4단계)를 판정한다
 * @param haystack   제목·브랜드·카테고리를 공백으로 이은 소문자. 세 필드를 따로 보기 전에 한 번의 contains 로 걸러 낸다 —
 *                   대부분의 상품은 어느 낱말에도 안 맞으므로 이 한 번이 검색 시간의 대부분을 정한다
 */
public record SearchDoc(String title, String brand, String category, Set<String> titleWords, String haystack) {

    /** 낱말 경계. 한글·영문·숫자가 아닌 것은 전부 구분자로 본다. 상품명의 대괄호·슬래시·괄호·가운뎃점을 가른다. */
    static final Pattern WORD_BREAK = Pattern.compile("[^0-9a-z가-힣]+");

    public static SearchDoc of(Product product) {
        String title = lower(product.title());
        String brand = lower(product.brand());
        String category = lower(product.category());
        return new SearchDoc(title, brand, category, Set.of(words(title)), title + ' ' + brand + ' ' + category);
    }

    /**
     * 동의어 묶음 하나가 이 상품의 어느 자리에서 맞았나. 제목 낱말 4 > 제목 부분 3 > 브랜드 2 > 카테고리 1 > 0.
     *
     * <p>단계를 넷으로 둔 이유는 실측이다. 제목 부분문자열만 보면 "우유" 검색에서 우유팩 키링과 우유니 캔들이
     * 실제 우유와 같은 취급을 받는다. 카테고리로만 맞은 것은 맨 뒤다 — 재현율은 얻되 첫 화면은 내주지 않는다(470).
     */
    byte tier(List<String> group) {
        boolean anywhere = false;
        for (String word : group) {
            if (haystack.contains(word)) {
                anywhere = true;
                break;
            }
        }
        if (!anywhere) {
            return 0;
        }
        for (String word : group) {
            if (titleWords.contains(word)) {
                return 4;
            }
        }
        for (String word : group) {
            if (title.contains(word)) {
                return 3;
            }
        }
        for (String word : group) {
            if (brand.contains(word)) {
                return 2;
            }
        }
        for (String word : group) {
            if (category.contains(word)) {
                return 1;
            }
        }
        return 0;
    }

    private static String[] words(String title) {
        String[] split = WORD_BREAK.split(title);
        // 앞이 구분자로 시작하면 빈 문자열이 첫 원소로 온다. Set.of 는 중복을 거절하므로 걸러 낸다.
        return java.util.Arrays.stream(split).filter(word -> !word.isEmpty()).distinct().toArray(String[]::new);
    }

    private static String lower(String field) {
        return field == null ? "" : field.toLowerCase(Locale.ROOT);
    }
}
