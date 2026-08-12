package com.ssafy.ieumgil.domain.festival;

import java.util.Arrays;

/**
 * TourAPI 축제 카테고리 코드 → 한글 라벨.
 *
 * <p>{@code Festival.category}에 저장된 TourAPI 원본 코드(EV01·EV02·EV03)를 사람이 읽는
 * 라벨로 접는다. 챗봇 요약({@code FestivalSummary})과 후보 수집({@code CandidateCollector})
 * 두 곳이 같은 라벨을 쓰는데, 각자 {@code switch}로 매핑하다 EV03을 한쪽만 명시하는 식으로
 * 텍스트가 갈라져 있었다(S15P11A107-243). 출력 자체는 같았으나(미지 코드는 양쪽 다 "행사"),
 * 다음 손질에서 조용히 어긋날 위험이 있어 단일 출처로 통일한다 — {@link RegionCode}와 같은 방식.
 */
public enum FestivalCategory {

    FESTIVAL("EV01", "축제"),
    PERFORMANCE("EV02", "공연"),
    EVENT("EV03", "행사");

    private final String code;
    private final String label;

    FestivalCategory(String code, String label) {
        this.code = code;
        this.label = label;
    }

    /** 원본 코드를 라벨로 접는다. 미지·누락 코드는 "행사"(EVENT)로 접는다 — 기존 두 매핑의 default와 같다. */
    public static String labelOf(String rawCode) {
        return Arrays.stream(values())
                .filter(category -> category.code.equals(rawCode))
                .findFirst()
                .map(category -> category.label)
                .orElse(EVENT.label);
    }
}
