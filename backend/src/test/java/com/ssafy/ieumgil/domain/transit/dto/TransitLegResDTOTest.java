package com.ssafy.ieumgil.domain.transit.dto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TransitLegResDTOTest {

    @Test
    @DisplayName("ODsay subPath를 우리 leg로 옮긴다")
    void subPath를_leg로_옮긴다() {
        List<OdsayRouteResponse.SubPath> subPaths = List.of(
                new OdsayRouteResponse.SubPath(3, 2, 150, null, null, null),
                new OdsayRouteResponse.SubPath(2, 38, 12000, "시청앞.덕수궁", "신분당선강남역",
                        List.of(new OdsayRouteResponse.Lane(null, "402", null))),
                new OdsayRouteResponse.SubPath(3, 4, 250, null, null, null));

        List<TransitLegResDTO.Leg> legs = TransitLegResDTO.fromSubPaths(subPaths);

        assertThat(legs).hasSize(3);
        assertThat(legs.get(0).type()).isEqualTo(TransitLegResDTO.LegType.WALK);
        assertThat(legs.get(0).durationMin()).isEqualTo(2);
        assertThat(legs.get(1).type()).isEqualTo(TransitLegResDTO.LegType.BUS);
        assertThat(legs.get(1).lineName()).isEqualTo("402");
        assertThat(legs.get(1).from()).isEqualTo("시청앞.덕수궁");
        assertThat(legs.get(1).to()).isEqualTo("신분당선강남역");
        assertThat(legs.get(1).durationMin()).isEqualTo(38);
    }

    @Test
    @DisplayName("trafficType 1~5는 그대로다 — 지하철·버스·도보·기차·고속버스")
    void trafficType_1부터_5는_그대로_매핑한다() {
        assertThat(legTypeOf(1)).isEqualTo(TransitLegResDTO.LegType.SUBWAY);
        assertThat(legTypeOf(2)).isEqualTo(TransitLegResDTO.LegType.BUS);
        assertThat(legTypeOf(3)).isEqualTo(TransitLegResDTO.LegType.WALK);
        assertThat(legTypeOf(4)).isEqualTo(TransitLegResDTO.LegType.TRAIN);
        assertThat(legTypeOf(5)).isEqualTo(TransitLegResDTO.LegType.EXPRESS_BUS);
    }

    @Test
    @DisplayName("[실측] trafficType 6은 시외버스, 7은 항공이다")
    void 버스와_항공_매핑() {
        // 실측: pt12 tt6 서부정류장→광주종합버스터미널 140분 20,900원
        //       pt13 tt7 김포국제공항→김해국제공항 65분 120,200원
        assertThat(TransitLegResDTO.LegType.of(6)).isEqualTo(TransitLegResDTO.LegType.EXPRESS_BUS);
        assertThat(TransitLegResDTO.LegType.of(7)).isEqualTo(TransitLegResDTO.LegType.AIR);
    }

    @Test
    @DisplayName("FERRY는 없다 — 157경로 실측에서 해운 관측 0건")
    void FERRY가_없다() {
        assertThat(TransitLegResDTO.LegType.values())
                .extracting(Enum::name)
                .doesNotContain("FERRY");
    }

    @Test
    @DisplayName("모르는 trafficType은 OTHER다")
    void 모르는_수단은_OTHER다() {
        assertThat(legTypeOf(99)).isEqualTo(TransitLegResDTO.LegType.OTHER);
    }

    @Test
    @DisplayName("지하철은 lane.name, 버스는 lane.busNo, 항공은 lane.airline을 노선명으로 쓴다")
    void 수단별로_노선명_출처가_다르다() {
        assertThat(lineNameOf(1, new OdsayRouteResponse.Lane("신분당선", null, null))).isEqualTo("신분당선");
        assertThat(lineNameOf(2, new OdsayRouteResponse.Lane(null, "402", null))).isEqualTo("402");
        assertThat(lineNameOf(7, new OdsayRouteResponse.Lane(null, null, "대한항공"))).isEqualTo("대한항공");
    }

    @Test
    @DisplayName("subPath가 null이면 빈 목록이다")
    void subPath가_없으면_빈_목록이다() {
        assertThat(TransitLegResDTO.fromSubPaths(null)).isEmpty();
    }

    private TransitLegResDTO.LegType legTypeOf(int trafficType) {
        return TransitLegResDTO.fromSubPaths(List.of(
                new OdsayRouteResponse.SubPath(trafficType, 10, 100, "A", "B", null))).get(0).type();
    }

    private String lineNameOf(int trafficType, OdsayRouteResponse.Lane lane) {
        return TransitLegResDTO.fromSubPaths(List.of(
                new OdsayRouteResponse.SubPath(trafficType, 10, 100, "A", "B", List.of(lane)))).get(0).lineName();
    }
}
