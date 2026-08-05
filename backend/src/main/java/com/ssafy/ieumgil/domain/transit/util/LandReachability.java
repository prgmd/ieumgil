package com.ssafy.ieumgil.domain.transit.util;

import com.ssafy.ieumgil.domain.transit.dto.OdsayRouteResponse.Path;
import com.ssafy.ieumgil.domain.transit.dto.OdsayRouteResponse.SubPath;

import java.util.List;

/**
 * 차로 갈 수 없는 목적지 판정. 섬 목록을 하드코딩하지 않고 <b>ODsay가 준 시외 경로가 전부
 * 항공을 거치는지</b>를 신호로 쓴다 — 목록은 끝나지 않고 틀리기 쉽지만 이 신호는 외부 API가
 * 이미 판단해 준 것이다.
 *
 * <p>카카오모빌리티는 서울→제주에도 {@code "길찾기 성공"}으로 527,600원·26,330초를 답한다.
 * 차로 갈 수 없는 경로에까지 답을 주므로, 육로 여부를 카카오에 물어 확인할 수는 없다.
 *
 * <p>항공 leg 판별은 {@code trafficType}이 아니라 역 ID 대역({@link StationIdBands#isAir})으로
 * 한다. 옛 판정자({@code hasNonRoadLeg})가 폐기된 이유가 정확히 이것이다 — 실측에서
 * {@code trafficType} 6(시외버스)·7(항공)이 우리 매핑과 뒤집혀 있어, 시외버스로 이어지는 멀쩡한
 * 육로 구간을 도서로 오판하고 자차·택시 후보를 지워 버렸다.
 *
 * <p>판정은 "시외 경로가 하나 이상 있고 <b>그 전부가</b> 항공 leg를 포함한다"다. "하나라도
 * 항공이면"으로 읽으면 안 된다 — 실측에서 서울→부산은 19경로 중 18개, 서울→여수는 22경로 중
 * 21개가 항공 없는 경로이고 그 구간은 차로 갈 수 있다. 반대로 서울→제주는 5경로 전부가 항공을
 * 거친다(항공 없는 경로 0개).
 */
public final class LandReachability {

    private LandReachability() {
    }

    /**
     * @param intercityPaths 이 구간의 <b>시외</b> 경로 목록(pathType 11·12·13·14·20). 시외 경로가
     *                       없는 시내 구간은 빈 목록이며, 그때는 판정하지 않는다({@code false}) —
     *                       빈 목록을 "전부 항공"으로 읽으면 시내 구간의 자차·택시가 전부 사라진다.
     * @return 이 구간을 차로 갈 수 없는지
     */
    public static boolean isLandUnreachable(List<Path> intercityPaths) {
        if (intercityPaths == null || intercityPaths.isEmpty()) {
            return false;
        }
        return intercityPaths.stream().allMatch(LandReachability::hasAirLeg);
    }

    private static boolean hasAirLeg(Path path) {
        List<SubPath> legs = path.subPath();
        if (legs == null) {
            return false;
        }
        return legs.stream().anyMatch(leg -> isAir(leg.startID()) || isAir(leg.endID()));
    }

    /** 역 ID가 없는 leg(도보 등)는 항공이 아니다 — null을 0으로 읽어 대역 밖으로 넘기지 않는다 */
    private static boolean isAir(Integer stationId) {
        return stationId != null && StationIdBands.isAir(stationId);
    }
}
