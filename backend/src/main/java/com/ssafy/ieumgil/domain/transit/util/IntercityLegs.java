package com.ssafy.ieumgil.domain.transit.util;

import com.ssafy.ieumgil.domain.transit.dto.OdsayRouteResponse.Path;
import com.ssafy.ieumgil.domain.transit.dto.OdsayRouteResponse.SubPath;
import com.ssafy.ieumgil.domain.transit.dto.TransitCandidateResDTO.TransitMode;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * ODsay 시외 경로(pathType 11·12·13·20)에서 시외 leg와 승·하차 지점을 뽑는다.
 *
 * <p>시외 경로에는 접근·이탈 leg가 섞이지 않는다 — 실측 216/216 경로에서 {@code subPath}
 * 전체가 시외 leg였다. 그래서 이 유틸은 subPath를 쪼개지 않고 그대로 쓴다. 대표 수단은
 * {@code trafficType}이 아니라 첫 leg의 역 ID 대역({@link StationIdBands})으로 정한다 —
 * 실측에서 6(시외버스)·7(항공)이 뒤집혀 있었다.
 *
 * <p>환승 경로(leg 2개, 실측 기차 162/246·버스 95/164)는 대표 수단이 첫 leg 기준이고,
 * {@code pathType 20}처럼 두 번째 leg가 다른 수단(기차→항공 등)일 수 있다.
 */
public record IntercityLegs(Path path, List<SubPath> legs, TransitMode mode) {

    /** 승·하차 좌표. ODsay 경도(x)·위도(y) 순서를 그대로 따른다 */
    public record Point(double x, double y) {
    }

    public boolean isTransfer() {
        return legs.size() > 1;
    }

    public Point boardingPoint() {
        SubPath first = legs.get(0);
        return new Point(first.startX(), first.startY());
    }

    public Point alightingPoint() {
        SubPath last = legs.get(legs.size() - 1);
        return new Point(last.endX(), last.endY());
    }

    /**
     * @return 시외 leg와 대표 수단. 첫 leg의 역 ID가 어느 대역에도 안 들면 empty다 —
     *         추측하지 않고, 호출자가 로그로 남기고 그 경로를 건너뛰게 한다.
     */
    public static Optional<IntercityLegs> of(Path path) {
        List<SubPath> legs = path.subPath();
        if (legs == null || legs.isEmpty()) {
            return Optional.empty();
        }
        Integer startId = legs.get(0).startID();
        if (startId == null) {
            return Optional.empty();
        }
        return StationIdBands.modeOf(startId).map(mode -> new IntercityLegs(path, legs, mode));
    }

    /**
     * 수단별 대표 경로. {@code transitCount} 최소 → 동률이면 {@code totalTime} 최소로 끊는다.
     *
     * <p>접근·이탈 소요가 이상적인 기준이지만, 그 값은 이미 고른 경로의 승차 지점으로
     * 별도 호출을 해야 나온다 — 경로 선택 전에는 알 수 없는 순환이다. 그래서 ODsay가
     * 경로 안에서 이미 주는 값(환승 횟수·총시간)으로 대신 정한다.
     */
    public static Map<TransitMode, IntercityLegs> pick(List<Path> paths) {
        Map<TransitMode, IntercityLegs> best = new LinkedHashMap<>();
        for (Path path : paths) {
            of(path).ifPresent(candidate -> best.merge(candidate.mode(), candidate, IntercityLegs::preferred));
        }
        return best;
    }

    private static IntercityLegs preferred(IntercityLegs a, IntercityLegs b) {
        int transitCountA = nullToMax(a.path().info().transitCount());
        int transitCountB = nullToMax(b.path().info().transitCount());
        if (transitCountA != transitCountB) {
            return transitCountA < transitCountB ? a : b;
        }
        return a.path().info().totalTime() <= b.path().info().totalTime() ? a : b;
    }

    private static int nullToMax(Integer value) {
        return value == null ? Integer.MAX_VALUE : value;
    }
}
