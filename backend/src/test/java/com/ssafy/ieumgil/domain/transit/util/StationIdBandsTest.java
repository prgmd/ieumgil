package com.ssafy.ieumgil.domain.transit.util;

import com.ssafy.ieumgil.domain.transit.dto.TransitCandidateResDTO.TransitMode;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class StationIdBandsTest {

    @Test
    @DisplayName("[실측] 3300xxx는 기차역 — tt4 1,034건 100%")
    void 기차역_대역() {
        assertThat(StationIdBands.modeOf(3300128)).contains(TransitMode.TRAIN);   // 서울역
        assertThat(StationIdBands.modeOf(3300108)).contains(TransitMode.TRAIN);   // 부산역
        assertThat(StationIdBands.modeOf(3300302)).contains(TransitMode.TRAIN);   // 오송
    }

    @Test
    @DisplayName("[실측] 3500xxx는 공항 — tt7 92건 100%. DomesticAirport ID와 일치")
    void 공항_대역() {
        assertThat(StationIdBands.modeOf(3500001)).contains(TransitMode.AIR);     // 김포
        assertThat(StationIdBands.modeOf(3500003)).contains(TransitMode.AIR);     // 제주
        assertThat(StationIdBands.modeOf(3500008)).contains(TransitMode.AIR);     // 광주공항
    }

    @Test
    @DisplayName("[실측] 3400·3600·4000xxx는 버스터미널 — tt5·tt6 혼용")
    void 버스터미널_대역() {
        assertThat(StationIdBands.modeOf(3400118)).contains(TransitMode.EXPRESS_BUS);  // 청주고속
        assertThat(StationIdBands.modeOf(3600210)).contains(TransitMode.EXPRESS_BUS);  // 서부정류장
        assertThat(StationIdBands.modeOf(4000057)).contains(TransitMode.EXPRESS_BUS);  // 서울고속
        assertThat(StationIdBands.modeOf(4000305)).contains(TransitMode.EXPRESS_BUS);  // 암태남강
    }

    @Test
    @DisplayName("모르는 대역은 empty — 추측하지 않는다")
    void 모르는_대역은_empty다() {
        assertThat(StationIdBands.modeOf(106171)).isEmpty();    // 시내 버스정류장
        assertThat(StationIdBands.modeOf(202)).isEmpty();       // 지하철역
        assertThat(StationIdBands.modeOf(9999999)).isEmpty();
    }
}
