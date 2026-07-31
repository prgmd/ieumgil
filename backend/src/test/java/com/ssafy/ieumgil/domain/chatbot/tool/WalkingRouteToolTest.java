package com.ssafy.ieumgil.domain.chatbot.tool;

import com.ssafy.ieumgil.domain.place.dto.PlaceResDTO;
import com.ssafy.ieumgil.domain.place.service.PlaceQueryService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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
        start = PlaceResDTO.Place.builder().placeId("1").name("숙소").address("addr1").lat(33.1).lng(126.1).category("숙박").build();
        end = PlaceResDTO.Place.builder().placeId("2").name("카페").address("addr2").lat(33.2).lng(126.2).category("카페").build();
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
        assertThat(result.startPlaceName()).isEqualTo("숙소");
        assertThat(result.endPlaceName()).isEqualTo("카페");
        assertThat(result.distanceM()).isEqualTo(850);
        assertThat(result.durationMin()).isEqualTo(12);
    }

    @Test
    void returnsNotFoundWhenStartPlaceUnresolved() {
        when(resolver.resolve("제주도", "존재하지않는곳")).thenReturn(Optional.empty());

        WalkingRouteResult result = tool.getWalkingRoute("존재하지않는곳", "카페");

        assertThat(result.found()).isFalse();
        assertThat(result.distanceM()).isNull();
    }

    @Test
    void returnsNotFoundWhenEndPlaceUnresolved() {
        when(resolver.resolve("제주도", "숙소")).thenReturn(Optional.of(start));
        when(resolver.resolve("제주도", "존재하지않는곳")).thenReturn(Optional.empty());

        WalkingRouteResult result = tool.getWalkingRoute("숙소", "존재하지않는곳");

        assertThat(result.found()).isFalse();
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
