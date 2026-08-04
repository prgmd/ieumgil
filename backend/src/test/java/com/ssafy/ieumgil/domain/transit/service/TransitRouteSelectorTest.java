package com.ssafy.ieumgil.domain.transit.service;

import com.ssafy.ieumgil.domain.transit.dto.OdsayRouteResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TransitRouteSelectorTest {

    private final TransitRouteSelector selector = new TransitRouteSelector();

    @Test
    @DisplayName("축마다 하나씩 골라 라벨을 붙인다")
    void 축별로_고른다() {
        OdsayRouteResponse.Path fastest = path(40, 2000, 2, 800, "402");
        OdsayRouteResponse.Path cheapest = path(60, 1200, 1, 900, "146");
        OdsayRouteResponse.Path fewestTransfer = path(55, 1800, 0, 1200, "9401");
        OdsayRouteResponse.Path leastWalk = path(58, 1900, 3, 200, "740");

        List<TransitRouteSelector.Selected> selected =
                selector.selectTop5(List.of(fastest, cheapest, fewestTransfer, leastWalk));

        assertThat(labelsOf(selected, fastest)).contains("최단 시간", "추천");
        assertThat(labelsOf(selected, cheapest)).contains("최저 요금");
        assertThat(labelsOf(selected, fewestTransfer)).contains("환승 최소");
        assertThat(labelsOf(selected, leastWalk)).contains("도보 최소");
    }

    @Test
    @DisplayName("같은 경로가 여러 축에 걸리면 하나로 합치고 라벨을 함께 붙인다")
    void 축이_겹치면_라벨을_합친다() {
        OdsayRouteResponse.Path best = path(40, 1200, 0, 200, "402");
        OdsayRouteResponse.Path other = path(60, 2000, 2, 900, "146");

        List<TransitRouteSelector.Selected> selected = selector.selectTop5(List.of(best, other));

        assertThat(selected).hasSize(2);
        assertThat(labelsOf(selected, best))
                .contains("추천", "최단 시간", "최저 요금", "환승 최소", "도보 최소");
    }

    @Test
    @DisplayName("5개를 넘기지 않는다")
    void 다섯개를_넘기지_않는다() {
        List<OdsayRouteResponse.Path> many = new ArrayList<>();
        for (int i = 0; i < 25; i++) {
            many.add(path(40 + i, 1500 + i * 10, i % 4, 300 + i * 10, "L" + i));
        }

        assertThat(selector.selectTop5(many)).hasSize(5);
    }

    @Test
    @DisplayName("축 중복으로 5개가 안 되면 paths 순서대로 채운다")
    void 부족하면_순서대로_채운다() {
        OdsayRouteResponse.Path best = path(40, 1200, 0, 200, "402");
        OdsayRouteResponse.Path second = path(50, 1500, 1, 400, "146");
        OdsayRouteResponse.Path third = path(55, 1700, 2, 500, "9401");

        List<TransitRouteSelector.Selected> selected = selector.selectTop5(List.of(best, second, third));

        assertThat(selected).hasSize(3);
        assertThat(selected.get(0).path()).isSameAs(best);
    }

    @Test
    @DisplayName("소요시간이 같아도 노선이 다르면 다른 경로로 본다")
    void 노선이_다르면_합치지_않는다() {
        OdsayRouteResponse.Path busRoute = path(44, 1500, 1, 400, "402");
        OdsayRouteResponse.Path subwayRoute = path(44, 1500, 1, 400, "2호선");

        List<TransitRouteSelector.Selected> selected = selector.selectTop5(List.of(busRoute, subwayRoute));

        assertThat(selected).hasSize(2);
    }

    @Test
    @DisplayName("요금이 null인 경로는 최저 요금 축에서 제외한다")
    void 요금_모르는_경로는_최저요금_축에서_뺀다() {
        OdsayRouteResponse.Path unknownFare = path(40, null, 0, 200, "KTX");
        OdsayRouteResponse.Path knownFare = path(60, 1500, 1, 400, "402");

        List<TransitRouteSelector.Selected> selected = selector.selectTop5(List.of(unknownFare, knownFare));

        assertThat(labelsOf(selected, knownFare)).contains("최저 요금");
        assertThat(labelsOf(selected, unknownFare)).doesNotContain("최저 요금");
    }

    private List<String> labelsOf(List<TransitRouteSelector.Selected> selected, OdsayRouteResponse.Path path) {
        return selected.stream().filter(s -> s.path() == path).findFirst().orElseThrow().labels();
    }

    private OdsayRouteResponse.Path path(int totalTime, Integer payment, int transfers, int walk, String lane) {
        return new OdsayRouteResponse.Path(
                2,
                new OdsayRouteResponse.Info(totalTime, payment, 9, 12000, walk, transfers, 0, "A", "B"),
                List.of(new OdsayRouteResponse.SubPath(
                        2, totalTime, 12000, "A", "B",
                        List.of(new OdsayRouteResponse.Lane(lane, lane, null)))));
    }
}
