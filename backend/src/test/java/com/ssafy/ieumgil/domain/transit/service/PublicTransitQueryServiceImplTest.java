package com.ssafy.ieumgil.domain.transit.service;

import com.ssafy.ieumgil.domain.transit.client.OdsayClient;
import com.ssafy.ieumgil.domain.transit.dto.OdsayRouteResponse;
import com.ssafy.ieumgil.domain.transit.dto.TransitResDTO;
import com.ssafy.ieumgil.domain.transit.exception.TransitErrorCode;
import com.ssafy.ieumgil.domain.transit.exception.TransitException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
        OdsayRouteResponse.Info info = new OdsayRouteResponse.Info(42, 1400, 13);
        OdsayRouteResponse.Path path = new OdsayRouteResponse.Path(2, info);
        when(odsayClient.searchPublicTransitRoute(37.4979, 127.0276, 37.5665, 127.1054, "BUS"))
                .thenReturn(Optional.of(path));

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
                .thenReturn(Optional.empty());

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
}
