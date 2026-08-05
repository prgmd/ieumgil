package com.ssafy.ieumgil.domain.transit.util;

import com.ssafy.ieumgil.domain.transit.dto.OdsayRouteResponse.Info;
import com.ssafy.ieumgil.domain.transit.dto.OdsayRouteResponse.Path;
import com.ssafy.ieumgil.domain.transit.dto.OdsayRouteResponse.SubPath;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LandReachabilityTest {

    @Test
    @DisplayName("[실측 5/5] 시외 경로가 전부 항공을 거치면 육로로 갈 수 없다 — 서울→제주")
    void 서울_제주는_육로로_갈_수_없다() {
        // 실측(서울시청→제주시청, 경로 5개): 항공 leg가 없는 경로가 0개다.
        // pathType 13 김포(3500001)→제주(3500003) / pathType 20은 기차·버스 + 항공 2leg다.
        List<Path> paths = List.of(
                path(13, airLeg(3500001, 3500003)),
                path(20, trainLeg(3300128, 3300302), airLeg(3500005, 3500003)),
                path(20, busLeg(4000035, 3601154), airLeg(3500014, 3500003)));

        assertThat(LandReachability.isLandUnreachable(paths)).isTrue();
    }

    @Test
    @DisplayName("[실측 18/19·21/22] 육로 경로가 하나라도 있으면 육로로 갈 수 있다 — 서울→부산·서울→여수")
    void 육로_경로가_하나라도_있으면_육로로_갈_수_있다() {
        // 실측 서울시청→부산시청은 19경로 중 18개, 서울시청→여수시청은 22경로 중 21개가 항공 없는
        // 경로다. "하나라도 항공이면 육로 불가"로 뒤집으면(any) 이 구간의 자차·택시가 부당하게
        // 사라진다 — 판정은 반드시 "전부 항공일 때"(all)여야 한다.
        List<Path> withOneAirAlternative = List.of(
                path(13, airLeg(3500001, 3500004)),                     // 김포→김해. 유일한 항공 대안
                path(11, trainLeg(3300128, 3300108)),                   // 서울→부산 KTX
                path(12, busLeg(4000057, 4000156)));                    // 서울고속터미널→부산종합

        assertThat(LandReachability.isLandUnreachable(withOneAirAlternative)).isFalse();
    }

    @Test
    @DisplayName("시외 경로가 없으면(시내 구간) 육로 판정을 하지 않는다")
    void 시외_경로가_없으면_판정하지_않는다() {
        // 시내 구간은 이 판정의 대상이 아니다. 빈 목록을 "전부 항공"으로 읽으면(allMatch의 기본값이
        // true다) 모든 시내 구간에서 자차·택시가 사라진다.
        assertThat(LandReachability.isLandUnreachable(List.of())).isFalse();
    }

    @Test
    @DisplayName("항공 leg는 trafficType이 아니라 역 ID 대역으로 판별한다")
    void 항공_leg는_역_ID_대역으로_판별한다() {
        // 옛 hasNonRoadLeg가 삭제된 이유가 trafficType 판별이었다 — 실측에서 6(시외버스)·7(항공)이
        // 뒤집혀 있어 멀쩡한 육로 구간을 도서로 오판했다. 그래서 이 판정자는 trafficType을 아예
        // 보지 않는다. 아래 두 픽스처는 trafficType과 대역을 일부러 어긋나게 둬 무엇이 판별자인지
        // 못 박는다(ODsay가 이렇게 준다는 뜻이 아니다 — 대역이 진실이라는 우리 규칙의 표현이다).
        Path busWithAirTrafficType = path(12, new SubPath(7, 140, null, "서부정류장", "광주종합", null,
                3600210, 4000135, null, null, null, null, 20900, null, null, null, null, null));
        Path airWithBusTrafficType = path(13, new SubPath(6, 65, null, "김포공항", "김해공항", null,
                3500001, 3500004, null, null, null, null, 120200, null, null, null, null, null));

        // trafficType 7이 붙었어도 버스터미널 대역(3600·4000)이면 항공이 아니다 → 육로로 갈 수 있다
        assertThat(LandReachability.isLandUnreachable(List.of(busWithAirTrafficType))).isFalse();
        // trafficType 6이 붙었어도 공항 대역(3500)이면 항공이다 → 육로로 갈 수 없다
        assertThat(LandReachability.isLandUnreachable(List.of(airWithBusTrafficType))).isTrue();
    }

    private Path path(int pathType, SubPath... legs) {
        return new Path(pathType,
                new Info(200, null, null, 300000, null, null, null, "출발", "도착", 50000, legs.length - 1),
                List.of(legs));
    }

    private SubPath trainLeg(int startId, int endId) {
        return new SubPath(4, 157, 325000, "출발역", "도착역", null,
                startId, endId, null, null, null, null, 59800, null, null, null, null, "KTX");
    }

    private SubPath busLeg(int startId, int endId) {
        return new SubPath(6, 140, 300000, "출발터미널", "도착터미널", null,
                startId, endId, null, null, null, null, 20900, null, null, null, null, null);
    }

    private SubPath airLeg(int startId, int endId) {
        return new SubPath(7, 65, 400000, "출발공항", "도착공항", null,
                startId, endId, null, null, null, null, 120200, null, null, null, null, null);
    }
}
