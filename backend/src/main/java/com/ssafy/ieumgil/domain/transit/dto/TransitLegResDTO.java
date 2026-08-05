package com.ssafy.ieumgil.domain.transit.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;

import java.util.List;

/**
 * 대중교통 경로의 구간별 상세.
 *
 * <p>ODsay {@code subPath}를 그대로 옮긴다. 이걸 버리면 "402번 버스로 시청앞에서
 * 강남역까지, 앞뒤로 도보 6분"이 "대중교통 44분"이 되고, 장거리에서는
 * "오송역 KTX → 광주송정, 광주공항 → 제주 항공"이 "대중교통 114분"이 된다.
 */
public class TransitLegResDTO {

    /** ODsay {@code trafficType} 매핑. 이 대응은 여기 한 곳에서만 한다 */
    public enum LegType {
        SUBWAY, BUS, WALK, TRAIN, EXPRESS_BUS, AIR, OTHER;

        /**
         * ODsay {@code trafficType} 매핑. 6·7은 실측으로 확정했다 —
         * 6은 시외버스(서부정류장→광주종합버스터미널 140분 20,900원),
         * 7은 항공(김포국제공항→김해국제공항 65분 120,200원)이다.
         * 해운은 157경로 실측에서 0건이라 상수를 두지 않는다.
         */
        static LegType of(int trafficType) {
            return switch (trafficType) {
                case 1 -> SUBWAY;
                case 2 -> BUS;
                case 3 -> WALK;
                case 4 -> TRAIN;
                case 5, 6 -> EXPRESS_BUS;   // 고속·시외버스. 같은 시간표 엔드포인트를 쓴다
                case 7 -> AIR;
                default -> OTHER;
            };
        }
    }

    @Schema(description = "경로 구간")
    @Builder
    public record Leg(
            LegType type,
            /** 지하철은 노선명, 버스는 번호, 항공은 항공사. 도보는 null */
            String lineName,
            /** 도보 구간은 ODsay가 승하차 지점을 주지 않아 null이다 */
            String from,
            String to,
            int durationMin
    ) {
    }

    public static List<Leg> fromSubPaths(List<OdsayRouteResponse.SubPath> subPaths) {
        if (subPaths == null) {
            return List.of();
        }
        return subPaths.stream()
                .map(sub -> Leg.builder()
                        .type(LegType.of(sub.trafficType()))
                        .lineName(lineNameOf(sub))
                        .from(sub.startName())
                        .to(sub.endName())
                        .durationMin(sub.sectionTime() == null ? 0 : sub.sectionTime())
                        .build())
                .toList();
    }

    public static String lineNameOf(OdsayRouteResponse.SubPath sub) {
        if (sub.lane() == null || sub.lane().isEmpty()) {
            return null;
        }
        OdsayRouteResponse.Lane lane = sub.lane().get(0);
        if (lane.name() != null) {
            return lane.name();
        }
        if (lane.busNo() != null) {
            return lane.busNo();
        }
        return lane.airline();
    }

    private TransitLegResDTO() {
    }
}
