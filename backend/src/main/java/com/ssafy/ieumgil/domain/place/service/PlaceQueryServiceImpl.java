package com.ssafy.ieumgil.domain.place.service;

import com.ssafy.ieumgil.domain.place.client.KakaoLocalClient;
import com.ssafy.ieumgil.domain.place.dto.KakaoAddressResponse;
import com.ssafy.ieumgil.domain.place.dto.KakaoPlaceResponse;
import com.ssafy.ieumgil.domain.place.dto.PlaceResDTO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class PlaceQueryServiceImpl implements PlaceQueryService {

    /**
     * 사용자 장소 검색 상한. SDK 시절과 같은 15건이다 — 카카오 keyword.json 의 기본
     * 페이지 크기가 15라 size 파라미터 없이 그대로 받으면 되고, 결과 수가 지금과
     * 같아져 서버 전환에 사용자 체감 변화가 없다.
     */
    private static final int USER_SEARCH_LIMIT = 15;

    private final KakaoLocalClient kakaoLocalClient;

    @Override
    public List<PlaceResDTO.Place> searchPlaces(String query, Double lat, Double lng) {
        return kakaoLocalClient.searchByKeyword(query, lat, lng).stream()
                .limit(USER_SEARCH_LIMIT)
                .map(this::toPlace)
                .toList();
    }

    @Override
    public List<PlaceResDTO.Place> searchPlacesInRect(String query, double swLat, double swLng,
                                                     double neLat, double neLng) {
        return kakaoLocalClient.searchByKeywordInRect(query, swLat, swLng, neLat, neLng).stream()
                .limit(CHATBOT_SEARCH_LIMIT)
                .map(this::toPlace)
                .toList();
    }

    @Override
    public Optional<PlaceResDTO.Address> reverseGeocode(double lat, double lng) {
        return kakaoLocalClient.coord2Address(lat, lng).map(this::toAddress);
    }

    @Override
    public Optional<PlaceResDTO.WalkingRoute> getWalkingRoute(
            double startLat, double startLng, double endLat, double endLng) {
        return kakaoLocalClient.getWalkingRoute(startLat, startLng, endLat, endLng)
                .map(r -> new PlaceResDTO.WalkingRoute(r.totalDistance(), toMinutes(r.totalTime())));
    }

    @Override
    public Optional<PlaceResDTO.TaxiRoute> getTaxiRoute(
            double startLat, double startLng, double endLat, double endLng) {
        return kakaoLocalClient.getDrivingRoute(startLat, startLng, endLat, endLng)
                .map(s -> new PlaceResDTO.TaxiRoute(
                        s.fare() != null ? s.fare().taxi() : 0,
                        s.fare() != null ? s.fare().toll() : 0,
                        s.distance(),
                        toMinutes(s.duration())));
    }

    /**
     * 카카오 길찾기의 소요시간(초) → 분.
     *
     * <p>초/분 변환은 <b>여기 한 곳</b>에서만 한다. 카카오 응답 DTO는 API 원본 그대로 초를 담고,
     * 도메인 DTO({@code PlaceResDTO})부터는 분이다 — 챗봇 tool·블록의 {@code durationMin}과 단위를
     * 맞추기 위함이다. 변환을 tool마다 두면 나중에 한쪽만 지워져도 조용히 틀린다.
     *
     * <p>올림인 이유: 59초를 "0분"이라 답하는 것보다 "1분"이 낫다.
     */
    private static int toMinutes(int seconds) {
        return (seconds + 59) / 60;
    }

    private PlaceResDTO.Place toPlace(KakaoPlaceResponse.Document d) {
        String address = (d.road_address_name() != null && !d.road_address_name().isBlank())
                ? d.road_address_name() : d.address_name();
        return PlaceResDTO.Place.builder()
                .placeId(d.id())
                .name(d.place_name())
                .address(address)
                .lat(Double.parseDouble(d.y()))
                .lng(Double.parseDouble(d.x()))
                .category(d.category_group_name())
                .categoryCode(d.category_group_code())
                // 빈 문자열은 "번호 없음"이다 — 그대로 두면 화면에 빈 줄이 생긴다
                .phone(blankToNull(d.phone()))
                .build();
    }

    private String blankToNull(String value) {
        return (value == null || value.isBlank()) ? null : value;
    }

    private PlaceResDTO.Address toAddress(KakaoAddressResponse.Document d) {
        String address = d.address() != null ? d.address().address_name() : null;
        String roadAddress = d.road_address() != null ? d.road_address().address_name() : null;
        return new PlaceResDTO.Address(address, roadAddress);
    }
}
