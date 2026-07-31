package com.ssafy.ieumgil.domain.chatbot.tool;

import java.util.List;

public record FestivalRecommendationResult(
        String regionName,
        String tripStartDate,
        String tripEndDate,
        List<FestivalSummary> festivals
) {
}
