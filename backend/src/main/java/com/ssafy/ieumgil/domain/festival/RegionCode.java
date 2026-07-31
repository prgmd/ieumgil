package com.ssafy.ieumgil.domain.festival;

import java.util.Arrays;
import java.util.Optional;

public enum RegionCode {

    SEOUL("11", "서울"),
    BUSAN("26", "부산"),
    DAEGU("27", "대구"),
    INCHEON("28", "인천"),
    GWANGJU("29", "광주"),
    DAEJEON("30", "대전"),
    ULSAN("31", "울산"),
    SEJONG("36", "세종"),
    GYEONGGI("41", "경기"),
    GANGWON("42", "강원"),
    CHUNGBUK("43", "충북"),
    CHUNGNAM("44", "충남"),
    JEONBUK("45", "전북"),
    JEONNAM("46", "전남"),
    GYEONGBUK("47", "경북"),
    GYEONGNAM("48", "경남"),
    JEJU("50", "제주");

    private final String code;
    private final String regionName;

    RegionCode(String code, String regionName) {
        this.code = code;
        this.regionName = regionName;
    }

    public String code() {
        return code;
    }

    public String regionName() {
        return regionName;
    }

    public static Optional<RegionCode> findByName(String destination) {
        if (destination == null || destination.isBlank()) {
            return Optional.empty();
        }
        return Arrays.stream(values())
                .filter(region -> destination.contains(region.regionName))
                .findFirst();
    }
}
