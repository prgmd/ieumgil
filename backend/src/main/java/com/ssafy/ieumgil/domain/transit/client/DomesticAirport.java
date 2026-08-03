package com.ssafy.ieumgil.domain.transit.client;

import java.util.Arrays;
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
        return Arrays.stream(values())
                .filter(airport -> airport.airportName.equals(name))
                .findFirst();
    }
}
