package com.ssafy.ieumgil.domain.chatbot.tool;

import java.util.List;

/**
 * 축제 추천 tool 결과.
 *
 * <p>여행 기간은 담지 않는다 — 시스템 프롬프트의 [Current trip] 블록에 이미 사실로 들어가 있어
 * 중복이고, 여기에 다시 실으면 모델이 축제 기간과 나란히 놓고 겹침 정도를 눈대중으로
 * 판단하도록 유도한다. 겹침은 {@link FestivalSummary#tripOverlap()}에 계산된 값으로 넘긴다.
 *
 * <p>{@code available}은 "이 지역·기간에 행사가 없다"와 "지금 조회가 안 된다"를 구분한다.
 * 둘이 같은 빈 목록이면 조회 장애 순간에 모델이 "행사가 없다"고 단언한다.
 */
public record FestivalRecommendationResult(
        String regionName,
        boolean available,
        List<FestivalSummary> festivals
) {

    /** 조회를 못 했을 때 — 지역명은 알고 있으므로 그대로 싣고, 목록만 비운 채 available=false로 내려간다. */
    public static FestivalRecommendationResult unavailable(String regionName) {
        return new FestivalRecommendationResult(regionName, false, List.of());
    }
}
