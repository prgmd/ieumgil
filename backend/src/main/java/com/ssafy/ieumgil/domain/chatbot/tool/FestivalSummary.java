package com.ssafy.ieumgil.domain.chatbot.tool;

import com.ssafy.ieumgil.domain.festival.FestivalCategory;
import com.ssafy.ieumgil.domain.festival.entity.Festival;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * 챗봇에 넘기는 축제 요약.
 *
 * <p>{@code tripOverlap}은 축제가 여행 기간과 실제로 겹치는 구간이며 서버가 계산한다.
 * 조회 쿼리(findOverlapping)는 최소 하루 겹침만 보장하므로 4일 여행에 마지막 하루만
 * 하는 축제도 결과에 들어온다. 이 값을 주지 않으면 모델이 여행 기간과 축제 기간을
 * 눈대중으로 비교해 "기간 내내 즐길 수 있다"처럼 부풀린다 — 2026-07-31 품질 평가에서
 * 실제로 발생했고, 시스템 프롬프트의 "기간을 부풀리지 말라" 지시로는 막히지 않았다.
 */
public record FestivalSummary(
        String title,
        String category,
        String addr,
        String eventStartDate,
        String eventEndDate,
        String tripOverlap
) {

    public static FestivalSummary from(Festival festival, LocalDate tripStartDate, LocalDate tripEndDate) {
        return new FestivalSummary(
                festival.getTitle(),
                FestivalCategory.labelOf(festival.getCategory()),
                festival.getAddr(),
                festival.getEventStartDate().toString(),
                festival.getEventEndDate().toString(),
                formatOverlap(festival, tripStartDate, tripEndDate)
        );
    }

    /** 겹침 구간 = max(시작일들) ~ min(종료일들). 쿼리가 겹침을 보장하므로 항상 유효한 구간이다. */
    private static String formatOverlap(Festival festival, LocalDate tripStartDate, LocalDate tripEndDate) {
        LocalDate from = max(festival.getEventStartDate(), tripStartDate);
        LocalDate to = min(festival.getEventEndDate(), tripEndDate);
        long days = ChronoUnit.DAYS.between(from, to) + 1;

        if (days == 1) {
            return "%s (1 day)".formatted(from);
        }
        return "%s ~ %s (%d days)".formatted(from, to, days);
    }

    private static LocalDate max(LocalDate a, LocalDate b) {
        return a.isAfter(b) ? a : b;
    }

    private static LocalDate min(LocalDate a, LocalDate b) {
        return a.isBefore(b) ? a : b;
    }
}
