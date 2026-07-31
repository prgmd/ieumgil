package com.ssafy.ieumgil.domain.chatbot.tool;

import com.ssafy.ieumgil.domain.festival.entity.Festival;

import java.time.LocalDate;

public record FestivalSummary(
        String title,
        String category,
        String addr,
        LocalDate eventStartDate,
        LocalDate eventEndDate
) {

    public static FestivalSummary from(Festival festival) {
        return new FestivalSummary(
                festival.getTitle(),
                festival.getCategory(),
                festival.getAddr(),
                festival.getEventStartDate(),
                festival.getEventEndDate()
        );
    }
}
