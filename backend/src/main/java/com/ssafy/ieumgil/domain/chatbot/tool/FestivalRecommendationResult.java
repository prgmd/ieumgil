package com.ssafy.ieumgil.domain.chatbot.tool;

import java.util.List;

/**
 * 축제 추천 tool 결과.
 *
 * <p>여행 기간은 담지 않는다 — 시스템 프롬프트의 [Current trip] 블록에 이미 사실로 들어가 있어
 * 중복이고, 여기에 다시 실으면 모델이 축제 기간과 나란히 놓고 겹침 정도를 눈대중으로
 * 판단하도록 유도한다. 겹침은 {@link FestivalSummary#tripOverlap()}에 계산된 값으로 넘긴다.
 */
public record FestivalRecommendationResult(
        String regionName,
        List<FestivalSummary> festivals
) {
}
