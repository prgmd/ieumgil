package com.ssafy.ieumgil.domain.chatbot.tool;

import com.ssafy.ieumgil.domain.festival.entity.Festival;

public record FestivalSummary(
        String title,
        String category,
        String addr,
        String eventStartDate,
        String eventEndDate
) {

    public static FestivalSummary from(Festival festival) {
        return new FestivalSummary(
                festival.getTitle(),
                toCategoryLabel(festival.getCategory()),
                festival.getAddr(),
                festival.getEventStartDate().toString(),
                festival.getEventEndDate().toString()
        );
    }

    private static String toCategoryLabel(String rawCategory) {
        return switch (rawCategory) {
            case "EV01" -> "축제";
            case "EV02" -> "공연";
            case "EV03" -> "행사";
            default -> "행사";
        };
    }
}
