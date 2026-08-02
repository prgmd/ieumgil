package com.ssafy.ieumgil.domain.chatbot.tool;

import com.ssafy.ieumgil.domain.place.client.KakaoLocalClient;
import com.ssafy.ieumgil.domain.place.dto.KakaoWalkingRouteResponse;
import com.ssafy.ieumgil.domain.place.dto.PlaceResDTO;
import com.ssafy.ieumgil.domain.place.service.PlaceQueryService;
import com.ssafy.ieumgil.domain.place.service.PlaceQueryServiceImpl;
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
class WalkingRouteToolTest {

    @Mock
    private PlaceQueryService placeQueryService;
    @Mock
    private KakaoPlaceCoordinateResolver resolver;

    private PlaceResDTO.Place start;
    private PlaceResDTO.Place end;

    private WalkingRouteTool tool;

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        start = PlaceResDTO.Place.builder().placeId("1").name("제주 게스트하우스 A").address("addr1").lat(33.1).lng(126.1).category("숙박").build();
        end = PlaceResDTO.Place.builder().placeId("2").name("제주 원두카페").address("addr2").lat(33.2).lng(126.2).category("카페").build();
        tool = new WalkingRouteTool("제주도", placeQueryService, resolver);
    }

    @Test
    void returnsFoundResultWithResolvedNamesWhenBothPlacesAndRouteExist() {
        when(resolver.resolve("제주도", "숙소")).thenReturn(Optional.of(start));
        when(resolver.resolve("제주도", "카페")).thenReturn(Optional.of(end));
        when(placeQueryService.getWalkingRoute(33.1, 126.1, 33.2, 126.2))
                .thenReturn(Optional.of(new PlaceResDTO.WalkingRoute(850, 12)));

        WalkingRouteResult result = tool.getWalkingRoute("숙소", "카페");

        assertThat(result.found()).isTrue();
        assertThat(result.startPlaceName()).isEqualTo("제주 게스트하우스 A");
        assertThat(result.endPlaceName()).isEqualTo("제주 원두카페");
        assertThat(result.distanceM()).isEqualTo(850);
        assertThat(result.durationMin()).isEqualTo(12);
    }

    @Test
    @DisplayName("모델에게 가는 값은 분이다 — 카카오 응답(초)이 그대로 새면 '도보 894분'이 된다")
    void deliversMinutesNotRawKakaoSeconds() {
        // 이 tool 테스트는 PlaceQueryService를 목으로 막아 두는 바람에 초/분 버그를 못 잡았다.
        // 그래서 여기만 실제 구현을 끼워 카카오 원본(초)부터 tool 결과(분)까지를 한 번에 본다.
        KakaoLocalClient kakaoLocalClient = org.mockito.Mockito.mock(KakaoLocalClient.class);
        // 시청→명동 실측값(2026-08-02 라이브 확인): 925m / 894초
        when(kakaoLocalClient.getWalkingRoute(33.1, 126.1, 33.2, 126.2)).thenReturn(Optional.of(
                new KakaoWalkingRouteResponse.Properties(925, 894, "https://map.kakao.com")));
        when(resolver.resolve("제주도", "숙소")).thenReturn(Optional.of(start));
        when(resolver.resolve("제주도", "카페")).thenReturn(Optional.of(end));
        WalkingRouteTool toolOverRealService =
                new WalkingRouteTool("제주도", new PlaceQueryServiceImpl(kakaoLocalClient), resolver);

        WalkingRouteResult result = toolOverRealService.getWalkingRoute("숙소", "카페");

        assertThat(result.durationMin()).isEqualTo(15);
    }

    @Test
    void returnsNotFoundWhenStartPlaceUnresolved() {
        when(resolver.resolve("제주도", "존재하지않는곳")).thenReturn(Optional.empty());

        WalkingRouteResult result = tool.getWalkingRoute("존재하지않는곳", "카페");

        assertThat(result.found()).isFalse();
        assertThat(result.distanceM()).isNull();
        verify(placeQueryService, never()).getWalkingRoute(anyDouble(), anyDouble(), anyDouble(), anyDouble());
    }

    @Test
    void returnsNotFoundWhenEndPlaceUnresolved() {
        when(resolver.resolve("제주도", "숙소")).thenReturn(Optional.of(start));
        when(resolver.resolve("제주도", "존재하지않는곳")).thenReturn(Optional.empty());

        WalkingRouteResult result = tool.getWalkingRoute("숙소", "존재하지않는곳");

        assertThat(result.found()).isFalse();
        verify(placeQueryService, never()).getWalkingRoute(anyDouble(), anyDouble(), anyDouble(), anyDouble());
    }

    @Test
    void returnsNotFoundWhenKakaoHasNoRoute() {
        when(resolver.resolve("제주도", "숙소")).thenReturn(Optional.of(start));
        when(resolver.resolve("제주도", "카페")).thenReturn(Optional.of(end));
        when(placeQueryService.getWalkingRoute(33.1, 126.1, 33.2, 126.2)).thenReturn(Optional.empty());

        WalkingRouteResult result = tool.getWalkingRoute("숙소", "카페");

        assertThat(result.found()).isFalse();
    }

    @Test
    void returnsNotFoundInsteadOfThrowingWhenResolverFails() {
        when(resolver.resolve(eq("제주도"), any())).thenThrow(new RuntimeException("kakao down"));

        WalkingRouteResult result = tool.getWalkingRoute("숙소", "카페");

        assertThat(result.found()).isFalse();
    }
}
