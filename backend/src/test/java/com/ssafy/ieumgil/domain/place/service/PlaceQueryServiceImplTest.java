package com.ssafy.ieumgil.domain.place.service;

import com.ssafy.ieumgil.domain.place.client.KakaoLocalClient;
import com.ssafy.ieumgil.domain.place.dto.KakaoAddressResponse;
import com.ssafy.ieumgil.domain.place.dto.KakaoDirectionsResponse;
import com.ssafy.ieumgil.domain.place.dto.KakaoPlaceResponse;
import com.ssafy.ieumgil.domain.place.dto.KakaoWalkingRouteResponse;
import com.ssafy.ieumgil.domain.place.dto.PlaceResDTO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PlaceQueryServiceImplTest {

    @Mock
    private KakaoLocalClient kakaoLocalClient;

    private PlaceQueryService placeQueryService;

    @Test
    void searchPlacesNormalizesFieldsAndPrefersRoadAddress() {
        placeQueryService = new PlaceQueryServiceImpl(kakaoLocalClient);
        KakaoPlaceResponse.Document doc = new KakaoPlaceResponse.Document(
                "26338954", "성산일출봉", "관광명소", "AT4",
                "제주 서귀포시 성산읍 성산리", "제주 서귀포시 성산읍 일출로 284-12", "064-123-4567",
                "126.9425", "33.4581");
        when(kakaoLocalClient.searchByKeyword("성산일출봉", 33.5, 126.5)).thenReturn(List.of(doc));

        List<PlaceResDTO.Place> result = placeQueryService.searchPlaces("성산일출봉", 33.5, 126.5);

        assertThat(result).hasSize(1);
        PlaceResDTO.Place place = result.get(0);
        assertThat(place.placeId()).isEqualTo("26338954");
        assertThat(place.name()).isEqualTo("성산일출봉");
        assertThat(place.address()).isEqualTo("제주 서귀포시 성산읍 일출로 284-12");
        assertThat(place.lat()).isEqualTo(33.4581);
        assertThat(place.lng()).isEqualTo(126.9425);
        assertThat(place.category()).isEqualTo("관광명소");
    }

    @Test
    void searchPlacesLimitsToTopFive() {
        placeQueryService = new PlaceQueryServiceImpl(kakaoLocalClient);
        List<KakaoPlaceResponse.Document> sixDocs = List.of(
                doc("1"), doc("2"), doc("3"), doc("4"), doc("5"), doc("6"));
        when(kakaoLocalClient.searchByKeyword("카페", null, null)).thenReturn(sixDocs);

        List<PlaceResDTO.Place> result = placeQueryService.searchPlaces("카페", null, null);

        assertThat(result).hasSize(5);
    }

    @Test
    void reverseGeocodeReturnsBothAddressFields() {
        placeQueryService = new PlaceQueryServiceImpl(kakaoLocalClient);
        KakaoAddressResponse.Document doc = new KakaoAddressResponse.Document(
                new KakaoAddressResponse.RoadAddress("제주 서귀포시 성산읍 일출로 284-12"),
                new KakaoAddressResponse.Address("제주 서귀포시 성산읍 성산리"));
        when(kakaoLocalClient.coord2Address(33.4581, 126.9425)).thenReturn(Optional.of(doc));

        Optional<PlaceResDTO.Address> result = placeQueryService.reverseGeocode(33.4581, 126.9425);

        assertThat(result).isPresent();
        assertThat(result.get().address()).isEqualTo("제주 서귀포시 성산읍 성산리");
        assertThat(result.get().roadAddress()).isEqualTo("제주 서귀포시 성산읍 일출로 284-12");
    }

    @Test
    void reverseGeocodeWithNoMatchReturnsEmpty() {
        placeQueryService = new PlaceQueryServiceImpl(kakaoLocalClient);
        when(kakaoLocalClient.coord2Address(100.0, 200.0)).thenReturn(Optional.empty());

        Optional<PlaceResDTO.Address> result = placeQueryService.reverseGeocode(100.0, 200.0);

        assertThat(result).isEmpty();
    }

    private KakaoPlaceResponse.Document doc(String id) {
        return new KakaoPlaceResponse.Document(id, "장소" + id, "카테고리", "CE7", "주소" + id, null, null, "127.0", "37.0");
    }

    @Test
    @DisplayName("도보 totalTime은 초다 — 분으로 바꿔 담는다")
    void getWalkingRouteConvertsSecondsToMinutes() {
        placeQueryService = new PlaceQueryServiceImpl(kakaoLocalClient);
        KakaoWalkingRouteResponse.Properties properties =
                new KakaoWalkingRouteResponse.Properties(4025, 3914, "https://map.kakao.com/route/walk/example");
        when(kakaoLocalClient.getWalkingRoute(33.4581, 126.9425, 33.46, 126.94)).thenReturn(Optional.of(properties));

        Optional<PlaceResDTO.WalkingRoute> result = placeQueryService.getWalkingRoute(33.4581, 126.9425, 33.46, 126.94);

        assertThat(result).isPresent();
        assertThat(result.get().distance()).isEqualTo(4025);
        // 3914초 = 65.2분 → 올림 66분. 그대로 두면 챗봇이 "도보 3914분"이라고 답한다.
        assertThat(result.get().durationMin()).isEqualTo(66);
    }

    @Test
    @DisplayName("1분 미만도 0분이 아니라 1분으로 올린다")
    void durationIsRoundedUpToAtLeastOneMinute() {
        placeQueryService = new PlaceQueryServiceImpl(kakaoLocalClient);
        when(kakaoLocalClient.getWalkingRoute(33.4581, 126.9425, 33.46, 126.94))
                .thenReturn(Optional.of(new KakaoWalkingRouteResponse.Properties(40, 59, "https://map.kakao.com")));

        Optional<PlaceResDTO.WalkingRoute> result = placeQueryService.getWalkingRoute(33.4581, 126.9425, 33.46, 126.94);

        assertThat(result).isPresent();
        assertThat(result.get().durationMin()).isEqualTo(1);
    }

    @Test
    void getWalkingRouteWithNoRouteReturnsEmpty() {
        placeQueryService = new PlaceQueryServiceImpl(kakaoLocalClient);
        when(kakaoLocalClient.getWalkingRoute(33.4581, 126.9425, 33.46, 126.94)).thenReturn(Optional.empty());

        Optional<PlaceResDTO.WalkingRoute> result = placeQueryService.getWalkingRoute(33.4581, 126.9425, 33.46, 126.94);

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("택시 duration도 초다 — 분으로 바꿔 담는다")
    void getTaxiRouteConvertsSecondsToMinutes() {
        placeQueryService = new PlaceQueryServiceImpl(kakaoLocalClient);
        KakaoDirectionsResponse.Summary summary = new KakaoDirectionsResponse.Summary(
                new KakaoDirectionsResponse.Fare(13200, 0), 8647, 1672);
        when(kakaoLocalClient.getDrivingRoute(37.5326, 127.0246, 37.5013, 127.0396))
                .thenReturn(Optional.of(summary));

        Optional<PlaceResDTO.TaxiRoute> result =
                placeQueryService.getTaxiRoute(37.5326, 127.0246, 37.5013, 127.0396);

        assertThat(result).isPresent();
        assertThat(result.get().fare()).isEqualTo(13200);
        assertThat(result.get().distance()).isEqualTo(8647);
        // 1672초 = 27.9분 → 올림 28분
        assertThat(result.get().durationMin()).isEqualTo(28);
    }

    @Test
    @DisplayName("택시 경로에 통행료가 함께 실린다 — 자차 요금 계산에 필요하다")
    void taxiRouteCarriesToll() {
        placeQueryService = new PlaceQueryServiceImpl(kakaoLocalClient);
        KakaoDirectionsResponse.Summary summary = new KakaoDirectionsResponse.Summary(
                new KakaoDirectionsResponse.Fare(8900, 2400), 8647, 1672);
        when(kakaoLocalClient.getDrivingRoute(37.5326, 127.0246, 37.5013, 127.0396))
                .thenReturn(Optional.of(summary));

        Optional<PlaceResDTO.TaxiRoute> result =
                placeQueryService.getTaxiRoute(37.5326, 127.0246, 37.5013, 127.0396);

        assertThat(result).isPresent();
        assertThat(result.get().toll()).isEqualTo(2400);
    }

    @Test
    void getTaxiRouteWithNoRouteReturnsEmpty() {
        placeQueryService = new PlaceQueryServiceImpl(kakaoLocalClient);
        when(kakaoLocalClient.getDrivingRoute(37.5326, 127.0246, 37.5013, 127.0396))
                .thenReturn(Optional.empty());

        Optional<PlaceResDTO.TaxiRoute> result =
                placeQueryService.getTaxiRoute(37.5326, 127.0246, 37.5013, 127.0396);

        assertThat(result).isEmpty();
    }

    @Test
    void searchPlacesInRectDelegatesToRectSearchAndCapsResults() {
        placeQueryService = new PlaceQueryServiceImpl(kakaoLocalClient);
        List<KakaoPlaceResponse.Document> documents = new java.util.ArrayList<>();
        for (int i = 0; i < 10; i++) {
            documents.add(new KakaoPlaceResponse.Document(
                    "id" + i, "장소" + i, "카페", "CE7", "제주 서귀포시", "제주 서귀포시 일출로", null, "126.94", "33.45"));
        }
        when(kakaoLocalClient.searchByKeywordInRect("카페", 33.44, 126.93, 33.47, 126.95))
                .thenReturn(documents);

        List<PlaceResDTO.Place> result = placeQueryService.searchPlacesInRect(
                "카페", 33.44, 126.93, 33.47, 126.95);

        // 일반 검색과 같은 상한을 적용한다 — 모델 입력 토큰과 후보 개수를 같은 기준으로 묶는다
        assertThat(result).hasSize(5);
        assertThat(result.get(0).placeId()).isEqualTo("id0");
        assertThat(result.get(0).lat()).isEqualTo(33.45);
    }

    @Test
    @DisplayName("카카오 응답의 category_group_code·phone 이 Place 에 실린다")
    void 카테고리코드와_전화번호를_싣는다() {
        placeQueryService = new PlaceQueryServiceImpl(kakaoLocalClient);
        KakaoPlaceResponse.Document doc = new KakaoPlaceResponse.Document(
                "12345", "스타벅스 강남점", "카페", "CE7",
                "서울 강남구 역삼동 1", "서울 강남구 테헤란로 1",
                "02-123-4567", "127.0276", "37.4979");
        when(kakaoLocalClient.searchByKeyword("스타벅스", null, null)).thenReturn(List.of(doc));

        PlaceResDTO.Place place = placeQueryService.searchPlaces("스타벅스", null, null).get(0);

        // categoryCode 가 없으면 프론트의 catFromKakaoGroup 이 전부 "명소"로 떨어진다
        assertThat(place.categoryCode()).isEqualTo("CE7");
        assertThat(place.category()).isEqualTo("카페");
        assertThat(place.phone()).isEqualTo("02-123-4567");
    }

    @Test
    @DisplayName("카카오가 전화번호를 빈 문자열로 주면 null 로 정규화한다")
    void 빈_전화번호는_null_이다() {
        placeQueryService = new PlaceQueryServiceImpl(kakaoLocalClient);
        KakaoPlaceResponse.Document doc = new KakaoPlaceResponse.Document(
                "999", "한강공원", "관광명소", "AT4",
                "서울 영등포구 여의동", "", "", "126.9", "37.5");
        when(kakaoLocalClient.searchByKeyword("공원", null, null)).thenReturn(List.of(doc));

        PlaceResDTO.Place place = placeQueryService.searchPlaces("공원", null, null).get(0);

        // 빈 문자열을 그대로 두면 프론트가 detail 에 ""를 넣고 말풍선에 빈 줄이 생긴다
        assertThat(place.phone()).isNull();
    }
}
