package com.ssafy.ieumgil.domain.place.service;

import com.ssafy.ieumgil.domain.place.client.KakaoLocalClient;
import com.ssafy.ieumgil.domain.place.dto.KakaoAddressResponse;
import com.ssafy.ieumgil.domain.place.dto.KakaoDirectionsResponse;
import com.ssafy.ieumgil.domain.place.dto.KakaoPlaceResponse;
import com.ssafy.ieumgil.domain.place.dto.KakaoWalkingRouteResponse;
import com.ssafy.ieumgil.domain.place.dto.PlaceResDTO;
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
                "26338954", "성산일출봉", "관광명소",
                "제주 서귀포시 성산읍 성산리", "제주 서귀포시 성산읍 일출로 284-12",
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
        return new KakaoPlaceResponse.Document(id, "장소" + id, "카테고리", "주소" + id, null, "127.0", "37.0");
    }

    @Test
    void getWalkingRouteMapsDistanceAndTime() {
        placeQueryService = new PlaceQueryServiceImpl(kakaoLocalClient);
        KakaoWalkingRouteResponse.Properties properties =
                new KakaoWalkingRouteResponse.Properties(4025, 3914, "https://map.kakao.com/route/walk/example");
        when(kakaoLocalClient.getWalkingRoute(33.4581, 126.9425, 33.46, 126.94)).thenReturn(Optional.of(properties));

        Optional<PlaceResDTO.WalkingRoute> result = placeQueryService.getWalkingRoute(33.4581, 126.9425, 33.46, 126.94);

        assertThat(result).isPresent();
        assertThat(result.get().distance()).isEqualTo(4025);
        assertThat(result.get().duration()).isEqualTo(3914);
    }

    @Test
    void getWalkingRouteWithNoRouteReturnsEmpty() {
        placeQueryService = new PlaceQueryServiceImpl(kakaoLocalClient);
        when(kakaoLocalClient.getWalkingRoute(33.4581, 126.9425, 33.46, 126.94)).thenReturn(Optional.empty());

        Optional<PlaceResDTO.WalkingRoute> result = placeQueryService.getWalkingRoute(33.4581, 126.9425, 33.46, 126.94);

        assertThat(result).isEmpty();
    }

    @Test
    void getTaxiRouteMapsFareDistanceDuration() {
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
        assertThat(result.get().duration()).isEqualTo(1672);
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
                    "id" + i, "장소" + i, "카페", "제주 서귀포시", "제주 서귀포시 일출로", "126.94", "33.45"));
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
}
