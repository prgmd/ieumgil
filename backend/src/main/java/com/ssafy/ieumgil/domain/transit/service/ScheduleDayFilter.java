package com.ssafy.ieumgil.domain.transit.service;

import com.ssafy.ieumgil.domain.transit.dto.OdsayTrainScheduleResponse;

import java.time.DayOfWeek;
import java.util.Map;

/**
 * 시간표의 운행 요일 표기와 여행 날짜를 맞춘다.
 *
 * <p>ODsay는 {@code runDay}에 "매일"·"토일"·"목"·"월수금"·"평일"·"주말"·"휴일"·"공휴일" 같은
 * 한글 표기를 준다. 여행 날짜의 요일에 운행하지 않는 편을 후보로 내면 사용자가 탈 수 없는 편을
 * 고르게 된다.
 *
 * <p><b>공휴일은 평일로 취급한다.</b> 공휴일 API를 붙이지 않는다 — 요금이 조금 다를 수
 * 있지만, 그 불확실성을 사용자에게 노출하는 것이 값 자체보다 더 혼란스럽다.
 */
public final class ScheduleDayFilter {

    private static final Map<Character, DayOfWeek> KOREAN_DAYS = Map.of(
            '월', DayOfWeek.MONDAY,
            '화', DayOfWeek.TUESDAY,
            '수', DayOfWeek.WEDNESDAY,
            '목', DayOfWeek.THURSDAY,
            '금', DayOfWeek.FRIDAY,
            '토', DayOfWeek.SATURDAY,
            '일', DayOfWeek.SUNDAY);

    /**
     * 그 요일에 운행하는지.
     *
     * <p>표기를 해석하지 못하면 {@code true}다 — 후보를 빼는 것보다 남기는 쪽이 낫다.
     * 사용자는 실제 출발 시각을 보고 판단할 수 있지만, 사라진 편은 볼 수 없다.
     */
    public static boolean runsOn(String runDay, DayOfWeek dayOfWeek) {
        if (runDay == null || runDay.isBlank() || runDay.contains("매일")) {
            return true;
        }
        // "휴일"/"공휴일"을 먼저 본다 — 공휴일은 판별하지 않으므로(공휴일은 평일로 취급) 통과시킨다.
        // "평일"과 "휴일"이 한 표기에 같이 들어간 경우("평일/휴일" 등)에도 이 검사가 먼저 걸려
        // 통과시킨다 — 편을 놓치는 것이 여분의 후보를 보여주는 것보다 나쁘다.
        if (runDay.contains("휴일")) {
            return true;
        }
        if (runDay.contains("평일")) {
            return dayOfWeek != DayOfWeek.SATURDAY && dayOfWeek != DayOfWeek.SUNDAY;
        }
        if (runDay.contains("주말")) {
            return dayOfWeek == DayOfWeek.SATURDAY || dayOfWeek == DayOfWeek.SUNDAY;
        }
        boolean hasAnyKnownDay = false;
        for (char c : runDay.toCharArray()) {
            DayOfWeek parsed = KOREAN_DAYS.get(c);
            if (parsed == null) {
                continue;
            }
            hasAnyKnownDay = true;
            if (parsed == dayOfWeek) {
                return true;
            }
        }
        return !hasAnyKnownDay;
    }

    /**
     * 그 요일에 해당하는 요금. 없으면 남은 값으로 폴백한다.
     *
     * <p>기존 {@code resolveFare}는 요일을 보지 않고 weekday를 먼저 집었다 — 주말 여행에
     * 평일 요금이 붙었다.
     */
    public static Integer fareFor(OdsayTrainScheduleResponse.DayFare breakdown, DayOfWeek dayOfWeek) {
        if (breakdown == null) {
            return null;
        }
        boolean weekend = dayOfWeek == DayOfWeek.SATURDAY || dayOfWeek == DayOfWeek.SUNDAY;
        Integer preferred = weekend ? breakdown.weekend() : breakdown.weekday();
        if (preferred != null) {
            return preferred;
        }
        if (breakdown.weekday() != null) {
            return breakdown.weekday();
        }
        if (breakdown.weekend() != null) {
            return breakdown.weekend();
        }
        return breakdown.holiday();
    }

    private ScheduleDayFilter() {
    }
}
