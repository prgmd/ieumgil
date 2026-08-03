package com.ssafy.ieumgil.domain.festival;

import lombok.extern.slf4j.Slf4j;

import java.util.Arrays;
import java.util.Comparator;
import java.util.Optional;

/**
 * 목적지 이름 → TourAPI 지역코드({@code lDongRegnCd}).
 *
 * <p>코드는 <b>TourAPI가 실제로 보내는 값</b> 기준이며, 표준 법정동 시도코드와 다른 곳이 있다.
 * 수집된 데이터의 주소를 직접 확인해 맞춘 값이다(2026-08-02):
 *
 * <ul>
 *   <li>강원 {@code 51} — 강원특별자치도 개편 반영. 예전 {@code 42}로는 조회되지 않는다.</li>
 *   <li>전북 {@code 52} — 전북특별자치도 개편 반영. 예전 {@code 45}로는 조회되지 않는다.</li>
 *   <li>세종 {@code 36110} — 시도 필드에 시군구 코드가 들어온다. {@code 36}이 아니다.</li>
 *   <li>광주·전남 {@code 12} — "전남광주통합특별시"로 <b>한 코드에 합쳐져</b> 온다.
 *       따라서 광주로 물어도 전남 축제가, 전남으로 물어도 광주 축제가 함께 나온다.</li>
 * </ul>
 *
 * <p>이 값이 틀리면 예외가 아니라 <b>조회 0건</b>으로 조용히 실패한다 — 실제로 강원·세종·
 * 광주·전남이 데이터가 있는데도 0건을 반환하고 있었다. 코드를 손볼 때는 반드시 수집된
 * 데이터의 {@code l_dong_regn_cd}와 주소를 대조할 것.
 */
@Slf4j
public enum RegionCode {

    SEOUL("11", "서울"),
    BUSAN("26", "부산"),
    DAEGU("27", "대구"),
    INCHEON("28", "인천"),
    GWANGJU("12", "광주"),
    DAEJEON("30", "대전"),
    ULSAN("31", "울산"),
    SEJONG("36110", "세종"),
    GYEONGGI("41", "경기"),
    GANGWON("51", "강원"),
    CHUNGBUK("43", "충북"),
    CHUNGNAM("44", "충남"),
    JEONBUK("52", "전북"),
    JEONNAM("12", "전남"),
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

    /**
     * 목적지 문자열에서 시도를 찾는다. 두 지역명이 함께 들어 있으면 <b>먼저 나오는</b> 쪽을 쓴다.
     *
     * <p>주소는 넓은 곳부터 쓰므로 앞에 있는 이름이 시도다 — "경기도 광주"는 경기다. 예전처럼
     * 선언 순서로 {@code findFirst}하면 {@code GWANGJU}가 {@code GYEONGGI}보다 위에 있다는
     * 이유만으로 전남·광주 축제를 받아왔고, 그 사실이 enum 상수 순서에 숨어 있었다.
     *
     * <p>광역명 17개만 본다. "전주"·"강릉"처럼 시·군 이름만 오면 매칭에 실패하는데, 그러면
     * 축제 tool 자체가 등록되지 않아 사용자는 이유를 알 수 없다. 그래서 흔적을 남긴다.
     */
    public static Optional<RegionCode> findByName(String destination) {
        if (destination == null || destination.isBlank()) {
            return Optional.empty();
        }
        Optional<RegionCode> matched = Arrays.stream(values())
                .filter(region -> destination.contains(region.regionName))
                .min(Comparator.comparingInt((RegionCode region) -> destination.indexOf(region.regionName))
                        .thenComparingInt(Enum::ordinal));
        if (matched.isEmpty()) {
            log.warn("목적지에서 시도를 찾지 못해 축제 추천을 건너뛴다 destination={}", destination);
        }
        return matched;
    }
}
