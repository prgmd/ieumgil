package com.ssafy.ieumgil.domain.chatbot.tool;

import com.ssafy.ieumgil.domain.place.client.KakaoLocalClient;
import com.ssafy.ieumgil.domain.place.dto.KakaoDirectionsResponse;
import com.ssafy.ieumgil.domain.place.dto.PlaceResDTO;
import com.ssafy.ieumgil.domain.place.service.PlaceQueryService;
import com.ssafy.ieumgil.domain.place.service.PlaceQueryServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TaxiRouteToolTest {

    @Mock
    private PlaceQueryService placeQueryService;
    @Mock
    private KakaoPlaceCoordinateResolver resolver;

    private PlaceResDTO.Place start;
    private PlaceResDTO.Place end;

    private TaxiRouteTool tool;

    @BeforeEach
    void setUp() {
        start = PlaceResDTO.Place.builder().placeId("1").name("제주 게스트하우스 A").address("addr1").lat(33.1).lng(126.1).category("숙박").build();
        end = PlaceResDTO.Place.builder().placeId("2").name("제주국제공항").address("addr2").lat(33.5).lng(126.5).category("교통").build();
        tool = new TaxiRouteTool("제주도", placeQueryService, resolver);
    }

    @Test
    void returnsFoundResultWithFareWhenBothPlacesAndRouteExist() {
        when(resolver.resolve("제주도", "숙소")).thenReturn(Optional.of(start));
        when(resolver.resolve("제주도", "공항")).thenReturn(Optional.of(end));
        when(placeQueryService.getTaxiRoute(33.1, 126.1, 33.5, 126.5))
                .thenReturn(Optional.of(new PlaceResDTO.TaxiRoute(35000, 42000, 40)));

        TaxiRouteResult result = tool.getTaxiRoute("숙소", "공항");

        assertThat(result.found()).isTrue();
        assertThat(result.startPlaceName()).isEqualTo("제주 게스트하우스 A");
        assertThat(result.endPlaceName()).isEqualTo("제주국제공항");
        assertThat(result.fare()).isEqualTo(35000);
        assertThat(result.distanceM()).isEqualTo(42000);
        assertThat(result.durationMin()).isEqualTo(40);
    }

    @Test
    @DisplayName("모델에게 가는 값은 분이다 — 카카오 응답(초)이 그대로 새면 '택시 1217분'이 된다")
    void deliversMinutesNotRawKakaoSeconds() {
        // 이 tool 테스트는 PlaceQueryService를 목으로 막아 두는 바람에 초/분 버그를 못 잡았다.
        // 그래서 여기만 실제 구현을 끼워 카카오 원본(초)부터 tool 결과(분)까지를 한 번에 본다.
        KakaoLocalClient kakaoLocalClient = org.mockito.Mockito.mock(KakaoLocalClient.class);
        // 서울시청→강남역 실측값(2026-08-02 라이브 확인): 10.7km / 1217초
        when(kakaoLocalClient.getDrivingRoute(33.1, 126.1, 33.5, 126.5)).thenReturn(Optional.of(
                new KakaoDirectionsResponse.Summary(new KakaoDirectionsResponse.Fare(13200, 0), 10700, 1217)));
        when(resolver.resolve("제주도", "숙소")).thenReturn(Optional.of(start));
        when(resolver.resolve("제주도", "공항")).thenReturn(Optional.of(end));
        TaxiRouteTool toolOverRealService =
                new TaxiRouteTool("제주도", new PlaceQueryServiceImpl(kakaoLocalClient), resolver);

        TaxiRouteResult result = toolOverRealService.getTaxiRoute("숙소", "공항");

        assertThat(result.durationMin()).isEqualTo(21);
    }

    @Test
    void returnsNotFoundWhenStartPlaceUnresolved() {
        when(resolver.resolve("제주도", "존재하지않는곳")).thenReturn(Optional.empty());

        TaxiRouteResult result = tool.getTaxiRoute("존재하지않는곳", "공항");

        assertThat(result.found()).isFalse();
        assertThat(result.fare()).isNull();
        verify(placeQueryService, never()).getTaxiRoute(anyDouble(), anyDouble(), anyDouble(), anyDouble());
    }

    @Test
    void returnsNotFoundWhenEndPlaceUnresolved() {
        when(resolver.resolve("제주도", "숙소")).thenReturn(Optional.of(start));
        when(resolver.resolve("제주도", "존재하지않는곳")).thenReturn(Optional.empty());

        TaxiRouteResult result = tool.getTaxiRoute("숙소", "존재하지않는곳");

        assertThat(result.found()).isFalse();
        verify(placeQueryService, never()).getTaxiRoute(anyDouble(), anyDouble(), anyDouble(), anyDouble());
    }

    @Test
    void returnsNotFoundWhenKakaoHasNoRoute() {
        when(resolver.resolve("제주도", "숙소")).thenReturn(Optional.of(start));
        when(resolver.resolve("제주도", "공항")).thenReturn(Optional.of(end));
        when(placeQueryService.getTaxiRoute(33.1, 126.1, 33.5, 126.5)).thenReturn(Optional.empty());

        TaxiRouteResult result = tool.getTaxiRoute("숙소", "공항");

        assertThat(result.found()).isFalse();
    }

    @Test
    void returnsNotFoundInsteadOfThrowingWhenResolverFails() {
        when(resolver.resolve(eq("제주도"), any())).thenThrow(new RuntimeException("kakao down"));

        TaxiRouteResult result = tool.getTaxiRoute("숙소", "공항");

        assertThat(result.found()).isFalse();
    }
}
