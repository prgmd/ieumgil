package com.ssafy.ieumgil.domain.festival;

import java.util.Arrays;
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

    public static Optional<RegionCode> findByName(String destination) {
        if (destination == null || destination.isBlank()) {
            return Optional.empty();
        }
        return Arrays.stream(values())
                .filter(region -> destination.contains(region.regionName))
                .findFirst();
    }
}
