package com.ssafy.ieumgil.domain.transit.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public record OdsayRouteResponse(Result result, @JsonProperty("error") Object error) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Result(List<Path> path) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Path(int pathType, Info info, List<SubPath> subPath) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Info(
            int totalTime,
            /**
             * 요금. <b>시내 경로에만 온다.</b> 시외(pathType 11)·항공(13)·복합(20)에는
             * 이 필드가 응답에 아예 없다 — primitive로 두면 누락이 0원이 된다.
             */
            Integer payment,
            /** 배차 간격. payment와 같은 이유로 Integer다 */
            Integer totalIntervalTime,
            /** 경로 실거리(m). 시내·시외 모두 온다 */
            Integer totalDistance,
            Integer totalWalk,
            Integer busTransitCount,
            Integer subwayTransitCount,
            String firstStartStation,
            String lastEndStation,
            /** 시외 경로의 요금. payment와 배타적 — 시내에는 없고 시외에는 있다(실측) */
            Integer totalPayment,
            /** 시외 경로의 환승 횟수 */
            Integer transitCount
    ) {
        public Info(int totalTime, Integer payment, Integer totalIntervalTime, Integer totalDistance,
                     Integer totalWalk, Integer busTransitCount, Integer subwayTransitCount,
                     String firstStartStation, String lastEndStation) {
            this(totalTime, payment, totalIntervalTime, totalDistance, totalWalk, busTransitCount,
                    subwayTransitCount, firstStartStation, lastEndStation, null, null);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SubPath(
            int trafficType,
            Integer sectionTime,
            Integer distance,
            String startName,
            String endName,
            List<Lane> lane,
            /** 시외 leg의 출발역 ID. 이름 검색 없이 시간표 API에 바로 쓴다(실측) */
            Integer startID,
            Integer endID,
            Double startX,
            Double startY,
            Double endX,
            Double endY,
            /** 시외 leg의 구간요금. 기차·고속버스·시외버스·항공 전부에 온다(실측) */
            Integer payment,
            Integer intervalTime,
            Integer intervalCount,
            /** 기차 특실요금 */
            Integer trainSpSeatPayment,
            /** 기차 특실 존재 여부 */
            String trainSpSeatYn,
            String trainType
    ) {
        public SubPath(int trafficType, Integer sectionTime, Integer distance,
                        String startName, String endName, List<Lane> lane) {
            this(trafficType, sectionTime, distance, startName, endName, lane,
                    null, null, null, null, null, null, null, null, null, null, null, null);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Lane(String name, String busNo, String airline) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record OdsayError(String code, String message) {
    }

    /**
     * ODsay 에러가 두 형태로 온다 — 실측 확인.
     *   경로 없음: {"error":{"msg":"검색결과가 없습니다.","code":"-99"}}
     *   인증 실패: {"error":[{"code":"500","message":"..."}]}
     * 타입을 하나로 못 박으면 한쪽이 Jackson 타입 불일치로 터지고,
     * 그게 "경로 없음"을 "API 장애"로 위장시킨다.
     *
     * @return 에러 코드. 에러가 없거나 예상과 다른 형태(응답 드리프트)면 null
     */
    public String errorCode() {
        if (error == null) return null;
        Object first = switch (error) {
            case List<?> list -> list.isEmpty() ? null : list.get(0);
            case Map<?, ?> map -> map;
            default -> null;
        };
        // 첫 원소가 Map이 아니면(에러 형태가 바뀌면) 캐스트로 터지는 대신 null로 접는다 — 드리프트가 500이 되지 않게.
        if (!(first instanceof Map<?, ?> map)) return null;
        Object code = map.get("code");
        return code == null ? null : String.valueOf(code);
    }
}
