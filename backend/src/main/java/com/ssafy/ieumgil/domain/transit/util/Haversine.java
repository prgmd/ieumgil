package com.ssafy.ieumgil.domain.transit.util;

/**
 * 두 좌표의 직선거리.
 *
 * <p>교통 후보를 만들 때 <b>외부 API를 부르기 전에</b> 도보·근거리 임계를 판정하는 데 쓴다.
 * 거리를 알려고 도보 API를 부르면 이미 쿼터를 쓴 뒤다 — 좌표만으로 계산되는 이 값이 공짜라
 * 호출 자체를 걸러낼 수 있다.
 */
public final class Haversine {

    private static final double EARTH_RADIUS_M = 6_371_000.0;

    private Haversine() {
    }

    public static double distanceMeters(double lat1, double lng1, double lat2, double lng2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return EARTH_RADIUS_M * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }
}
