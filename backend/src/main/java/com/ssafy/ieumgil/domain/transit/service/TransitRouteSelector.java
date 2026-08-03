package com.ssafy.ieumgil.domain.transit.service;

import com.ssafy.ieumgil.domain.transit.dto.OdsayRouteResponse;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * 시내 대중교통 경로 목록에서 사용자에게 보여줄 5개를 고른다.
 *
 * <p>ODsay는 한 구간에 25개까지 준다. 하나만 쓰면 "요금이 싼 것"과 "빠른 것" 사이의
 * 선택권이 사라지고, 25개를 다 내리면 사용자가 고를 수 없다. 그래서 <b>서로 다른 기준으로
 * 최적인 경로들</b>을 뽑고, 왜 뽑혔는지를 라벨로 함께 내려보낸다 — 라벨이 없으면 사용자가
 * 카드 다섯 장을 직접 비교해야 한다.
 *
 * <p>외부 호출을 추가하지 않는다. 한 번 받은 응답 안에서 고른다.
 */
@Component
public class TransitRouteSelector {

    private static final int MAX_CANDIDATES = 5;

    public record Selected(OdsayRouteResponse.Path path, List<String> labels) {
    }

    public List<Selected> selectTop5(List<OdsayRouteResponse.Path> paths) {
        if (paths.isEmpty()) {
            return List.of();
        }

        // 같은 경로가 여러 축에서 뽑히므로 키(노선 순열)로 모아 라벨을 합친다
        Map<String, List<String>> labelsByKey = new LinkedHashMap<>();
        Map<String, OdsayRouteResponse.Path> pathByKey = new LinkedHashMap<>();

        record Axis(String label, Function<List<OdsayRouteResponse.Path>, Optional<OdsayRouteResponse.Path>> pick) {
        }
        List<Axis> axes = List.of(
                new Axis("추천", ps -> Optional.of(ps.get(0))),
                new Axis("최단 시간", ps -> min(ps, p -> (long) p.info().totalTime())),
                // 요금을 모르는 경로(시외)는 이 축에서 제외한다 — null을 0으로 취급하면 항상 최저가가 된다
                new Axis("최저 요금", ps -> ps.stream()
                        .filter(p -> p.info().payment() != null)
                        .min(Comparator.comparingLong(p -> p.info().payment()))),
                new Axis("환승 최소", ps -> min(ps, p -> (long) transferCount(p))),
                new Axis("도보 최소", ps -> min(ps, p -> (long) nullToMax(p.info().totalWalk()))));

        for (Axis axis : axes) {
            axis.pick().apply(paths).ifPresent(picked -> {
                String key = keyOf(picked);
                pathByKey.putIfAbsent(key, picked);
                labelsByKey.computeIfAbsent(key, k -> new ArrayList<>()).add(axis.label());
            });
        }

        // 축이 겹쳐 5개가 안 되면 ODsay 순서대로 채운다
        for (OdsayRouteResponse.Path path : paths) {
            if (pathByKey.size() >= MAX_CANDIDATES) {
                break;
            }
            String key = keyOf(path);
            pathByKey.putIfAbsent(key, path);
            labelsByKey.putIfAbsent(key, new ArrayList<>());
        }

        return pathByKey.entrySet().stream()
                .limit(MAX_CANDIDATES)
                .map(e -> new Selected(e.getValue(), List.copyOf(labelsByKey.get(e.getKey()))))
                .toList();
    }

    private static Optional<OdsayRouteResponse.Path> min(
            List<OdsayRouteResponse.Path> paths, Function<OdsayRouteResponse.Path, Long> key) {
        return paths.stream().min(Comparator.comparingLong(key::apply));
    }

    public static int transferCount(OdsayRouteResponse.Path path) {
        return nullToZero(path.info().busTransitCount()) + nullToZero(path.info().subwayTransitCount());
    }

    /**
     * 동일 경로 판정 키. (수단, 노선명, 승차, 하차) 순열을 쓴다.
     *
     * <p>{@code totalTime}으로 판정하면 서로 다른 노선이 같은 시간일 때 하나로 합쳐져
     * 후보가 줄어든다 — 버스 402와 지하철 2호선이 둘 다 44분인 경우가 실제로 있다.
     */
    private static String keyOf(OdsayRouteResponse.Path path) {
        if (path.subPath() == null || path.subPath().isEmpty()) {
            return path.pathType() + ":" + path.info().totalTime() + ":" + path.info().payment();
        }
        StringBuilder sb = new StringBuilder();
        for (OdsayRouteResponse.SubPath sub : path.subPath()) {
            sb.append(sub.trafficType()).append('|')
              .append(laneNameOf(sub)).append('|')
              .append(sub.startName()).append('>')
              .append(sub.endName()).append(';');
        }
        return sb.toString();
    }

    static String laneNameOf(OdsayRouteResponse.SubPath sub) {
        if (sub.lane() == null || sub.lane().isEmpty()) {
            return "";
        }
        OdsayRouteResponse.Lane lane = sub.lane().get(0);
        if (lane.name() != null) {
            return lane.name();
        }
        if (lane.busNo() != null) {
            return lane.busNo();
        }
        return lane.airline() == null ? "" : lane.airline();
    }

    private static int nullToZero(Integer value) {
        return value == null ? 0 : value;
    }

    private static int nullToMax(Integer value) {
        return value == null ? Integer.MAX_VALUE : value;
    }
}
