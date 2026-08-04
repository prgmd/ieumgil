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
    @DisplayName("trafficType 7종을 전부 매핑한다")
    void trafficType을_전부_매핑한다() {
        assertThat(legTypeOf(1)).isEqualTo(TransitLegResDTO.LegType.SUBWAY);
        assertThat(legTypeOf(2)).isEqualTo(TransitLegResDTO.LegType.BUS);
        assertThat(legTypeOf(3)).isEqualTo(TransitLegResDTO.LegType.WALK);
        assertThat(legTypeOf(4)).isEqualTo(TransitLegResDTO.LegType.TRAIN);
        assertThat(legTypeOf(5)).isEqualTo(TransitLegResDTO.LegType.EXPRESS_BUS);
        assertThat(legTypeOf(6)).isEqualTo(TransitLegResDTO.LegType.AIR);
        assertThat(legTypeOf(7)).isEqualTo(TransitLegResDTO.LegType.FERRY);
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
        assertThat(lineNameOf(6, new OdsayRouteResponse.Lane(null, null, "대한항공"))).isEqualTo("대한항공");
    }

    @Test
    @DisplayName("항공·해운 구간이 있으면 육로로 이어지지 않는다고 판정한다")
    void 항공이나_해운이_있으면_육로가_아니다() {
        List<TransitLegResDTO.Leg> withAir = TransitLegResDTO.fromSubPaths(List.of(
                new OdsayRouteResponse.SubPath(6, 70, 368207, "청주국제공항", "제주국제공항",
                        List.of(new OdsayRouteResponse.Lane(null, null, "이스타항공")))));
        List<TransitLegResDTO.Leg> onlyRoad = TransitLegResDTO.fromSubPaths(List.of(
                new OdsayRouteResponse.SubPath(2, 38, 12000, "A", "B",
                        List.of(new OdsayRouteResponse.Lane(null, "402", null)))));

        assertThat(TransitLegResDTO.hasNonRoadLeg(withAir)).isTrue();
        assertThat(TransitLegResDTO.hasNonRoadLeg(onlyRoad)).isFalse();
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
