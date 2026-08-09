package com.ssafy.ieumgil.domain.transit.util;

/**
 * ODsay가 쓰는 {@code "HH:mm"} 시각 표기. <b>24시를 넘긴 시각을 그대로 표기한다</b> —
 * 심야 버스는 {@code "24:10"}·{@code "25:20"}·{@code "26:00"}으로 온다(실측: 서울→부산
 * 고속버스 51편 중 8편, {@code lastTime}도 {@code "26:00"}).
 *
 * <p>{@link java.time.LocalTime#parse}는 이 표기에서 {@code DateTimeParseException}을 던진다.
 * 그래서 시간표에 심야편이 하나라도 섞이면 그 수단 후보 전체가 조회 실패로 죽었다 — 서울→부산
 * 고속버스가 실제로 그렇게 사라졌다. 편 하나를 못 읽는 것이 수단 하나를 지우는 근거가 될 수는 없다.
 *
 * <p>그래서 시각을 {@code LocalTime}이 아니라 <b>자정 기준 분</b>으로 다룬다. 24시 넘김은
 * 1440 이상의 값이 되고, 비교·정렬·차이 계산이 모두 그 정수 하나로 끝난다. 표시 문자열은
 * ODsay가 준 것을 그대로 쓴다 — {@code "24:10"}은 한국 사용자에게 "심야 0시 10분"으로 읽히고,
 * {@code "00:10"}으로 바꾸면 오히려 어느 날인지가 사라진다.
 */
public final class OdsayClock {

    public static final int MINUTES_PER_DAY = 1440;

    private OdsayClock() {
    }

    /**
     * {@code "HH:mm"} → 자정 기준 분. {@code "26:00"}은 1560이다.
     *
     * @throws IllegalArgumentException 형식이 아니거나 분이 0~59를 벗어날 때. 시(hour)는
     *                                 상한을 두지 않는다 — ODsay가 어디까지 올려 쓰는지 모르고,
     *                                 상한을 잘못 잡으면 이 클래스가 고치려는 문제를 재현한다.
     */
    public static int minutesOf(String hhmm) {
        if (hhmm == null) {
            throw new IllegalArgumentException("시각이 null이다");
        }
        int colon = hhmm.indexOf(':');
        if (colon < 1 || colon != hhmm.length() - 3) {
            throw new IllegalArgumentException("HH:mm 형식이 아니다: " + hhmm);
        }
        try {
            int hour = Integer.parseInt(hhmm.substring(0, colon));
            int minute = Integer.parseInt(hhmm.substring(colon + 1));
            if (hour < 0 || minute < 0 || minute > 59) {
                throw new IllegalArgumentException("시각 범위를 벗어났다: " + hhmm);
            }
            return hour * 60 + minute;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("숫자가 아니다: " + hhmm, e);
        }
    }

    /**
     * 자정 기준 분 → {@code "HH:mm"}. 1440 이상은 ODsay 표기를 따라 {@code "24:10"}처럼
     * 그대로 올려 적는다 — {@code % 1440}으로 접으면 다음 날 편이 오늘 새벽 편으로 보인다.
     */
    public static String format(int minutes) {
        if (minutes < 0) {
            throw new IllegalArgumentException("음수 시각: " + minutes);
        }
        return String.format("%02d:%02d", minutes / 60, minutes % 60);
    }

    /**
     * 출발 → 도착 소요(분). 도착이 출발보다 이르면 자정을 넘긴 편으로 보고 하루를 더한다
     * (23:30 출발 05:10 도착 = 340분). 둘 다 24시 넘김 표기여도 같은 정수 축에서 계산된다.
     */
    public static int minutesBetween(String departureAt, String arrivalAt) {
        int elapsed = minutesOf(arrivalAt) - minutesOf(departureAt);
        return elapsed < 0 ? elapsed + MINUTES_PER_DAY : elapsed;
    }
}
