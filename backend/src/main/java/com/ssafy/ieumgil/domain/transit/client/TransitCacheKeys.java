package com.ssafy.ieumgil.domain.transit.client;

/**
 * ODsay 외부 조회 캐시 키. 순수 함수만 — Redis도 시계도 모른다.
 *
 * <p>좌표를 소수 4자리로 반올림하는 이유: 4자리는 약 11m다. 블록 좌표는 사용자가 지도에서
 * 찍은 값이라 같은 장소를 두 번 찍어도 소수 6~7자리가 미세하게 다르고, 그대로 키에 쓰면
 * 사람 눈에 같은 경로가 캐시 미스가 된다. 11m 차이로 대중교통 경로가 달라지지는 않는다.
 *
 * <p>{@code mode}를 키에 넣는 이유: {@code OdsayClient}가 mode로 {@code SearchPathType}을
 * 바꾼다(BUS 2·SUBWAY 1·나머지 0=통합). 빼면 버스 전용 결과가 통합 조회의 답으로 나간다.
 *
 * <p>시간표 키에 <b>날짜가 없다</b>: ODsay 시간표 호출 자체가 날짜를 받지 않고 운행 요일
 * ({@code runDay})이 붙은 전체 시간표를 준다 — 요일 필터는 {@code TransitScheduleQueryServiceImpl}이
 * 그 뒤에 한다. 그래서 한 엔트리가 모든 날짜를 커버한다.
 */
public final class TransitCacheKeys {

    private static final String PREFIX = "transit:";

    private TransitCacheKeys() {
    }

    public static String route(double startLat, double startLng, double endLat, double endLng, String mode) {
        return PREFIX + "route:" + mode + ":"
                + round4(startLat) + ":" + round4(startLng) + ":"
                + round4(endLat) + ":" + round4(endLng);
    }

    /** @param kind {@code train}·{@code bus}·{@code air} — 시간표 엔드포인트가 셋이다 */
    public static String schedule(String kind, int startStationId, int endStationId) {
        return PREFIX + "sched:" + kind + ":" + startStationId + ":" + endStationId;
    }

    /**
     * 소수 4자리 문자열. {@code Math.round}로 만들어 {@code -0.0}이나 지수 표기가 섞이지
     * 않게 하고, 같은 좌표가 항상 같은 문자열이 되게 한다.
     */
    private static String round4(double coordinate) {
        return String.format("%.4f", coordinate);
    }
}
