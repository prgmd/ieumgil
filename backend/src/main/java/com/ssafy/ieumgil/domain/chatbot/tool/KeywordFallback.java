package com.ssafy.ieumgil.domain.chatbot.tool;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 카카오 키워드 검색이 0건일 때 쓸 재검색어를 만든다.
 *
 * <p>라이브 측정에서 카카오 로컬 키워드 검색은 <b>장소 종류어 한 개</b>만 제대로 받는다.
 * 수식어를 붙인 서술구(<code>부산 실내 관광지 아이</code>, <code>야경 전망대</code>,
 * <code>사진 촬영 명소</code>)는 0건인 반면 <code>부산 박물관</code>·<code>술집</code> 같은
 * 단일 종류어는 결과가 나온다. 모델의 자체 재시도는 불안정해(포기하면 카드 0건) 프롬프트가
 * 아니라 코드에서 결정적으로 막는다.
 *
 * <p><b>붙여쓰기가 축약보다 먼저다 — 의도를 보존하기 때문이다.</b> 공백 제거
 * (<code>키즈 카페</code> → <code>키즈카페</code>)는 사용자가 요구한 조건을 그대로 들고
 * 카카오 카테고리 표기(붙임말)와 한국어 띄어쓰기 흔들림만 흡수한다. 반면 단일 토큰 축약은
 * 조건을 <b>버려서</b> 결과를 얻는다(<code>키즈 카페</code> → <code>카페</code>는 키즈 조건이
 * 사라진 일반 카페를 준다). 그래서 의도를 지키는 쪽을 먼저 시도하고, 그게 0건일 때만 버린다.
 *
 * <p>축약 후보는 토큰을 <b>오른쪽부터</b> 고른다 — 한국어 명사구는 핵심어가 뒤에 오므로
 * 오른쪽 토큰이 장소 종류어일 확률이 높다. 상한 {@value #MAX_FALLBACKS}는 카카오 쿼터와
 * 응답 지연 때문이다(원 호출 포함 최대 4회).
 */
public final class KeywordFallback {

    private static final int MAX_FALLBACKS = 3;

    private KeywordFallback() {
    }

    /**
     * 재시도 후보만 순서대로 돌려준다(원본은 제외). 최대 {@value #MAX_FALLBACKS}개.
     *
     * <p>순서는 붙여쓰기 → 오른쪽 토큰 → 그 왼쪽 토큰이다.
     * {@code null}·공백·단일 토큰이면 붙일 것도 자를 것도 없으므로 빈 목록이다.
     */
    public static List<String> candidatesFor(String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return List.of();
        }
        String trimmed = keyword.trim();
        String[] tokens = trimmed.split("\\s+");
        if (tokens.length <= 1) {
            return List.of();
        }
        Set<String> seen = new LinkedHashSet<>();
        seen.add(trimmed); // 원본 자체는 후보가 아니다
        List<String> candidates = new ArrayList<>(MAX_FALLBACKS);

        addIfNew(String.join("", tokens), seen, candidates);
        for (int i = tokens.length - 1; i >= 0 && candidates.size() < MAX_FALLBACKS; i--) {
            addIfNew(tokens[i], seen, candidates);
        }
        return List.copyOf(candidates);
    }

    private static void addIfNew(String candidate, Set<String> seen, List<String> candidates) {
        if (candidates.size() < MAX_FALLBACKS && seen.add(candidate)) {
            candidates.add(candidate);
        }
    }
}
