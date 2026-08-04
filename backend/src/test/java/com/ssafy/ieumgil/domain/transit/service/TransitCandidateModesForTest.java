package com.ssafy.ieumgil.domain.transit.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.ssafy.ieumgil.domain.project.entity.TransportPref;
import com.ssafy.ieumgil.domain.transit.service.TransitCandidateServiceImpl.RoadMode;
import java.util.List;
import org.junit.jupiter.api.Test;

class TransitCandidateModesForTest {

    // WALK 잡음을 피하려 도보 최대 거리보다 확실히 먼 값을 쓴다.
    private static final double FAR = 50_000.0;

    @Test
    void carOnly_keepsCarAndTaxi_noTransit() {
        var modes = TransitCandidateServiceImpl.modesFor(List.of(TransportPref.CAR), FAR);
        assertThat(modes.transit()).isFalse();
        assertThat(modes.road()).contains(RoadMode.CAR, RoadMode.TAXI);
    }

    @Test
    void publicOnly_taxiAndTransit_noCar() {
        var modes = TransitCandidateServiceImpl.modesFor(List.of(TransportPref.PUBLIC), FAR);
        assertThat(modes.transit()).isTrue();
        assertThat(modes.road()).contains(RoadMode.TAXI).doesNotContain(RoadMode.CAR);
    }

    @Test
    void both_carAndTaxiAndTransit() {
        var modes = TransitCandidateServiceImpl.modesFor(
                List.of(TransportPref.CAR, TransportPref.PUBLIC), FAR);
        assertThat(modes.transit()).isTrue();
        assertThat(modes.road()).contains(RoadMode.CAR, RoadMode.TAXI);
    }

    @Test
    void empty_defaultsToTransit() {
        var modes = TransitCandidateServiceImpl.modesFor(List.of(), FAR);
        assertThat(modes.transit()).isTrue();
        assertThat(modes.road()).doesNotContain(RoadMode.CAR);
    }
}
