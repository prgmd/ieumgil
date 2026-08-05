package com.ssafy.ieumgil.domain.chatbot.service;

import com.ssafy.ieumgil.domain.project.entity.Project;
import com.ssafy.ieumgil.domain.project.entity.TransportPref;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * 여행 메타데이터를 시스템 프롬프트 꼬리로 만든다 (BOT-03 컨텍스트).
 *
 * <p>모델이 날짜 뺄셈을 하게 두면 틀리므로 여행 일수는 서버가 계산해 넣는다.
 * 값이 없는 항목을 (unset)으로 명시하는 것도 의도다 — 줄을 빼면 모델은 그 정보가
 * 없다는 사실조차 모르고, 명시하면 사용자에게 되물을 근거가 된다.
 *
 * <p>라벨을 영어로 두는 이유는 GMS 토크나이저가 한글을 영어보다 훨씬 잘게 쪼개기
 * 때문이다(tool description을 영어로 확정한 것과 같은 판단). 값은 지명·키워드라
 * 한글이 불가피하다.
 */
public final class TripContextBuilder {

    private static final String UNSET = "(unset)";

    private TripContextBuilder() {
    }

    public static String build(Project project, Integer headcount) {
        boolean hasDestination = project.getDestination() != null && !project.getDestination().isBlank();
        boolean hasDates = project.getStartDate() != null && project.getEndDate() != null;

        // 목적지도 기간도 없으면 (unset)만 늘어선 껍데기가 되므로 아예 넣지 않는다
        if (!hasDestination && !hasDates) {
            return "";
        }

        StringBuilder block = new StringBuilder("\n[Current trip]\n");
        block.append("destination: ").append(hasDestination ? project.getDestination() : UNSET).append('\n');
        block.append("dates: ").append(hasDates ? formatDates(project.getStartDate(), project.getEndDate()) : UNSET)
                .append('\n');
        block.append("headcount: ").append(headcount != null ? headcount : UNSET).append('\n');

        List<TransportPref> transportPrefs = project.getTransportPrefs();
        if (transportPrefs != null && !transportPrefs.isEmpty()) {
            block.append("transport: ").append(toTransportLabel(transportPrefs)).append('\n');
        }

        Integer targetBudget = project.getTargetBudget();
        if (targetBudget != null) {
            block.append("targetBudget: ").append(targetBudget).append('\n');
        }

        List<String> keywords = project.getKeywords();
        if (keywords != null && !keywords.isEmpty()) {
            block.append("keywords: ").append(String.join(", ", keywords)).append('\n');
        }

        return block.toString();
    }

    private static String formatDates(LocalDate startDate, LocalDate endDate) {
        long days = ChronoUnit.DAYS.between(startDate, endDate) + 1;
        return "%s ~ %s (%d %s)".formatted(startDate, endDate, days, days == 1 ? "day" : "days");
    }

    /** enum 이름을 그대로 노출하면 모델이 "CAR"라고 답할 여지가 있어 사람이 읽는 말로 바꾼다 */
    private static String toTransportLabel(List<TransportPref> prefs) {
        if (prefs == null || prefs.isEmpty()) {
            return "대중교통";
        }
        return prefs.stream().map(p -> switch (p) {
            case CAR -> "자가용";
            case PUBLIC -> "대중교통";
        }).collect(java.util.stream.Collectors.joining(", "));
    }
}
