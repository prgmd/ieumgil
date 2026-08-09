package com.ssafy.ieumgil.domain.transit.service;

import com.ssafy.ieumgil.domain.transit.client.OdsayClient;
import com.ssafy.ieumgil.domain.transit.dto.OdsayRouteResponse;
import com.ssafy.ieumgil.domain.transit.dto.TransitResDTO;
import com.ssafy.ieumgil.domain.transit.exception.TransitErrorCode;
import com.ssafy.ieumgil.domain.transit.exception.TransitException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PublicTransitQueryServiceImplTest {

    @Mock
    private OdsayClient odsayClient;

    private PublicTransitQueryServiceImpl service;

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        service = new PublicTransitQueryServiceImpl(odsayClient);
    }

    @Test
    void confirmedRouteReturnsOdsayValues() {
        OdsayRouteResponse.Info info = new OdsayRouteResponse.Info(42, 1400, 13, null, null, null, null, null, null);
        OdsayRouteResponse.Path path = new OdsayRouteResponse.Path(2, info, null);
        when(odsayClient.searchPublicTransitRoute(37.4979, 127.0276, 37.5665, 127.1054, "BUS"))
                .thenReturn(List.of(path));

        TransitResDTO.Route result = service.getRoute(37.4979, 127.0276, 37.5665, 127.1054, "BUS");

        assertThat(result.durationMin()).isEqualTo(42);
        assertThat(result.fare()).isEqualTo(1400);
        assertThat(result.intervalMin()).isEqualTo(13);
        assertThat(result.estimated()).isFalse();
        assertThat(result.fareConfidence()).isEqualTo(TransitResDTO.FareConfidence.CONFIRMED);
    }

    @Test
    void odsayFailurePropagatesException() {
        when(odsayClient.searchPublicTransitRoute(37.4979, 127.0276, 37.5665, 127.1054, "SUBWAY"))
                .thenThrow(new TransitException(TransitErrorCode.ODSAY_API_CALL_FAILED));

        assertThatThrownBy(() ->
                service.getRoute(37.4979, 127.0276, 37.5665, 127.1054, "SUBWAY"))
                .isInstanceOf(TransitException.class)
                .hasFieldOrPropertyWithValue("code", TransitErrorCode.ODSAY_API_CALL_FAILED);
    }

    @Test
    void emptyOdsayResultThrowsRouteNotFound() {
        when(odsayClient.searchPublicTransitRoute(37.4979, 127.0276, 37.5665, 127.1054, "BUS"))
                .thenReturn(List.of());

        assertThatThrownBy(() ->
                service.getRoute(37.4979, 127.0276, 37.5665, 127.1054, "BUS"))
                .isInstanceOf(TransitException.class)
                .hasFieldOrPropertyWithValue("code", TransitErrorCode.ROUTE_NOT_FOUND);
    }

    @Test
    void unsupportedModeThrowsTransitException() {
        assertThatThrownBy(() ->
                service.getRoute(37.4979, 127.0276, 37.5665, 127.1054, "WALK"))
                .isInstanceOf(TransitException.class)
                .hasFieldOrPropertyWithValue("code", TransitErrorCode.UNSUPPORTED_MODE);
    }

    @Test
    @DisplayName("통합 대중교통 조회는 버스·지하철을 함께 고려한 경로를 돌려준다")
    void combinedRouteUsesIntegratedSearchType() {
        // Info(totalTime, payment, totalIntervalTime, totalDistance, totalWalk, busTransitCount, subwayTransitCount, firstStartStation, lastEndStation)
        OdsayRouteResponse.Info info = new OdsayRouteResponse.Info(25, 1400, 8, null, null, null, null, null, null);
        given(odsayClient.searchPublicTransitRoute(37.5, 127.0, 37.6, 127.1, "TRANSIT"))
                .willReturn(List.of(new OdsayRouteResponse.Path(0, info, null)));

        TransitResDTO.Route route = service.getCombinedRoute(37.5, 127.0, 37.6, 127.1);

        assertThat(route.durationMin()).isEqualTo(25);
        assertThat(route.fare()).isEqualTo(1400);
        assertThat(route.intervalMin()).isEqualTo(8);
        assertThat(route.fareConfidence()).isEqualTo(TransitResDTO.FareConfidence.CONFIRMED);
    }

    @Test
    @DisplayName("경로가 없으면 ROUTE_NOT_FOUND다 — 기존 getRoute와 같은 계약")
    void combinedRouteThrowsWhenNotFound() {
        given(odsayClient.searchPublicTransitRoute(anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyString()))
                .willReturn(List.of());

        assertThatThrownBy(() -> service.getCombinedRoute(37.5, 127.0, 37.6, 127.1))
                .isInstanceOf(TransitException.class);
    }

    @Test
    @DisplayName("요금 필드가 없는 시외 경로는 fare=null, fareConfidence=UNKNOWN이다")
    void 시외_경로는_요금을_모른다고_답한다() {
        // ODsay 시외 경로(pathType=11)는 payment·totalIntervalTime을 주지 않는다 — 실측 확인됨
        OdsayRouteResponse.Info info = new OdsayRouteResponse.Info(138, null, null, null, null, null, null, null, null);
        OdsayRouteResponse.Path path = new OdsayRouteResponse.Path(11, info, null);
        given(odsayClient.searchPublicTransitRoute(anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyString()))
                .willReturn(List.of(path));

        TransitResDTO.Route route = service.getCombinedRoute(37.5666, 126.9784, 35.1151, 129.0413);

        assertThat(route.durationMin()).isEqualTo(138);
        assertThat(route.fare()).isNull();
        assertThat(route.fareConfidence()).isEqualTo(TransitResDTO.FareConfidence.UNKNOWN);
        assertThat(route.intervalMin()).isNull();
    }

    @Test
    @DisplayName("요금 필드가 있는 시내 경로는 fare 실값 + CONFIRMED다")
    void 시내_경로는_요금을_확정으로_답한다() {
        OdsayRouteResponse.Info info = new OdsayRouteResponse.Info(44, 1500, 9, 12841, 400, 1, 0, "시청", "강남");
        OdsayRouteResponse.Path path = new OdsayRouteResponse.Path(2, info, null);
        given(odsayClient.searchPublicTransitRoute(anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyString()))
                .willReturn(List.of(path));

        TransitResDTO.Route route = service.getCombinedRoute(37.5666, 126.9784, 37.4979, 127.0276);

        assertThat(route.fare()).isEqualTo(1500);
        assertThat(route.fareConfidence()).isEqualTo(TransitResDTO.FareConfidence.CONFIRMED);
        assertThat(route.intervalMin()).isEqualTo(9);
    }

    @Test
    @DisplayName("요금이 실제로 0원이면 UNKNOWN이 아니라 CONFIRMED다")
    void 실제_0원과_누락을_구분한다() {
        OdsayRouteResponse.Info info = new OdsayRouteResponse.Info(5, 0, 3, 400, 400, 0, 0, "A", "B");
        OdsayRouteResponse.Path path = new OdsayRouteResponse.Path(2, info, null);
        given(odsayClient.searchPublicTransitRoute(anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyString()))
                .willReturn(List.of(path));

        TransitResDTO.Route route = service.getCombinedRoute(37.5, 127.0, 37.5, 127.0);

        assertThat(route.fare()).isZero();
        assertThat(route.fareConfidence()).isEqualTo(TransitResDTO.FareConfidence.CONFIRMED);
    }

    @Test
    @DisplayName("getCombinedRoutes는 ODsay가 준 경로를 전부 반환한다")
    void 경로_목록을_그대로_반환한다() {
        OdsayRouteResponse.Path p1 = pathOf(2, 44, 1500);
        OdsayRouteResponse.Path p2 = pathOf(2, 52, 1250);
        OdsayRouteResponse.Path p3 = pathOf(1, 48, 1400);
        given(odsayClient.searchPublicTransitRoute(anyDouble(), anyDouble(), anyDouble(), anyDouble(), eq("TRANSIT")))
                .willReturn(List.of(p1, p2, p3));

        List<OdsayRouteResponse.Path> paths = service.getCombinedRoutes(37.5666, 126.9784, 37.4979, 127.0276);

        assertThat(paths).hasSize(3);
        assertThat(paths.get(1).info().totalTime()).isEqualTo(52);
    }

    @Test
    @DisplayName("경로가 없으면 ROUTE_NOT_FOUND를 던진다")
    void 경로가_없으면_예외다() {
        given(odsayClient.searchPublicTransitRoute(anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyString()))
                .willReturn(List.of());

        assertThatThrownBy(() -> service.getCombinedRoutes(37.5, 127.0, 37.6, 127.1))
                .isInstanceOf(TransitException.class);
    }

    private OdsayRouteResponse.Path pathOf(int pathType, int totalTime, Integer payment) {
        return new OdsayRouteResponse.Path(
                pathType,
                new OdsayRouteResponse.Info(totalTime, payment, 9, 12000, 400, 1, 0, "A", "B"),
                List.of());
    }
}
