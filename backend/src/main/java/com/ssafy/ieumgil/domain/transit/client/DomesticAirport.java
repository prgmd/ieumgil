package com.ssafy.ieumgil.domain.transit.client;

import java.util.Arrays;
import java.util.Map;
import java.util.Optional;

public enum DomesticAirport {

    GIMPO(3500001, "김포"),
    INCHEON(3500002, "인천"),
    JEJU(3500003, "제주"),
    BUSAN(3500004, "부산"),
    CHEONGJU(3500005, "청주"),
    DAEGU(3500006, "대구"),
    YANGYANG(3500007, "양양"),
    GWANGJU(3500008, "광주"),
    GUNSAN(3500010, "군산"),
    POHANG(3500011, "포항"),
    YEOSU(3500012, "여수"),
    ULSAN(3500013, "울산"),
    WONJU(3500014, "원주"),
    JINJU(3500015, "진주");

    /**
     * 도시명이 등록된 공항명과 다른 경우의 별칭. ODsay 경로 응답의 {@code firstStartStation}/
     * {@code lastEndStation}은 공항명이 아니라 도시명을 준다(예: 서울→부산 조회 시 "서울").
     * 나머지 13개 공항은 도시명 자체가 이미 등록된 {@code airportName}이라 별도 별칭이
     * 필요 없다 — 서울(김포)만 도시명과 등록명이 달라 실제로 항공 후보가 조용히 빠졌다.
     * 김해(부산)·사천(진주)은 도시명과 다른 실제 공항명이라 안전하게 추가한다.
     * 무안처럼 이 목록에 없는 공항은 추가하지 않는다 — 광주공항과 물리적으로 다른 공항을
     * 근처 공항으로 추측해 연결하면 안 된다.
     */
    private static final Map<String, DomesticAirport> CITY_ALIASES = Map.of(
            "서울", GIMPO,
            "김해", BUSAN,
            "사천", JINJU
    );

    private final int stationId;
    private final String airportName;

    DomesticAirport(int stationId, String airportName) {
        this.stationId = stationId;
        this.airportName = airportName;
    }

    public int stationId() {
        return stationId;
    }

    public String airportName() {
        return airportName;
    }

    public static Optional<DomesticAirport> findByName(String name) {
        if (name == null) {
            return Optional.empty();
        }
        String normalized = stripAirportSuffix(name.trim());
        return Arrays.stream(values())
                .filter(airport -> airport.airportName.equals(normalized))
                .findFirst()
                .or(() -> Optional.ofNullable(CITY_ALIASES.get(normalized)));
    }

    /**
     * ODsay는 첫 매칭 시외 경로가 항공 경로 자신일 때 도시명이 아니라 공항 전체 명칭
     * ("김해국제공항"·"제주국제공항")을 준다 — 도시명일 때만 통하던 기존 매칭·별칭 조회가
     * 이 접미사 때문에 실패해 항공 후보가 조용히 빠졌다. "국제공항"을 먼저 검사해야 한다 —
     * "공항"만 먼저 벗기면 "김해국제공항"이 "김해국제"로 잘못 남는다.
     */
    private static String stripAirportSuffix(String trimmed) {
        if (trimmed.endsWith("국제공항")) {
            return trimmed.substring(0, trimmed.length() - "국제공항".length());
        }
        if (trimmed.endsWith("공항")) {
            return trimmed.substring(0, trimmed.length() - "공항".length());
        }
        return trimmed;
    }
}
