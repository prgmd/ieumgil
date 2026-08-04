package com.ssafy.ieumgil.domain.transit.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;

import java.util.List;

public class TransitCandidateResDTO {

    /**
     * 이동수단.
     *
     * <p>{@code TRANSIT}은 <b>시내</b> 버스·지하철 통합이다. 둘을 따로 내지 않는 이유는
     * 사용자가 고르는 단위가 "대중교통이냐 택시냐"이지 "버스냐 지하철이냐"가 아니기 때문이다.
     *
     * <p>시외는 {@code TRAIN}·{@code EXPRESS_BUS}·{@code AIR}로 <b>나눈다.</b> 시내와 달리
     * 여기서는 수단이 곧 선택의 축이다 — 서울→부산에서 기차 59,800원 2시간 37분과
     * 고속버스 39,700원 4시간은 사용자가 실제로 저울질하는 대안이다. 하나로 뭉치면
     * 그 선택이 사라진다.
     */
    public enum TransitMode {
        TRANSIT("대중교통"),
        TRAIN("기차"),
        EXPRESS_BUS("고속·시외버스"),
        AIR("항공"),
        TAXI("택시"),
        CAR("자차"),
        WALK("도보");

        private final String label;

        TransitMode(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    @Schema(description = "교통 후보 계산 결과")
    @Builder
    public record Result(List<Segment> segments) {
    }

    @Schema(description = "블록 사이 한 구간")
    @Builder
    public record Segment(
            Long fromBlockId,
            Long toBlockId,
            /** 시외 구간인지. ODsay pathType으로 판정한다 */
            boolean intercity,
            /** 시간표 기반으로 실제 출발편을 골랐는지 */
            boolean timetableApplied,
            /** timetableApplied=false인 이유. true면 null */
            String timetableSkipReason,
            /** 출발편 선정 기준 시각(HH:mm). 시간표를 적용하지 않은 구간은 null */
            String referenceAt,
            /** 모든 조회가 실패하면 null이다 — 프론트는 그 구간만 비워 두고 안내한다 */
            TransitMode defaultMode,
            List<Candidate> candidates
    ) {
    }

    /**
     * 한 수단의 계산 결과.
     *
     * <p>{@code available=false}는 <b>조회 실패</b>에만 쓴다. 거리 때문에 제외된 도보는
     * 이 목록에 아예 없다 — 둘을 같은 플래그로 뭉개면 프론트가 "먼 것인가 API가 죽은 것인가"를
     * 구분하지 못한다.
     */
    @Schema(description = "이동수단 후보")
    @Builder
    public record Candidate(
            TransitMode mode,
            String label,
            boolean available,
            Integer durationMin,
            Integer fare,
            TransitResDTO.FareConfidence fareConfidence,
            /** 대중교통 배차 간격. 나머지 수단은 null */
            Integer intervalMin,
            Integer distanceM,
            /** 시내 대중교통 후보의 선정 이유("최단 시간" 등). 나머지 수단은 null */
            List<String> labels,
            /** 환승 횟수. 시외는 0, 자차·택시·도보는 null */
            Integer transferCount,
            /** 도보 거리(m). 시내 대중교통만 */
            Integer walkMeters,
            /**
             * 이 값을 그대로 믿으면 안 되는 이유. 없으면 null.
             *
             * <p>현재는 어떤 후보도 이 필드를 채우지 않는다 — 육로 경로가 없는 구간(도서 목적지 등)은
             * 자차·택시 후보 자체를 만들지 않기로 했다(예전의 페리 경고 + ESTIMATE 강등은 폐기했다).
             * 다른 신뢰도 문제가 생기면 이 필드를 재사용한다.
             */
            String caution,
            /** 경로 구간 상세. 자차·택시·도보는 null */
            List<TransitLegResDTO.Leg> legs,
            /** 시외 출발편 후보. 시내·자차·택시·도보는 null */
            List<Departure> departures
    ) {

        /** 조회 실패 — mode·label만 채우고 나머지는 비운 채 available만 false다 */
        public static Candidate unavailable(TransitMode mode) {
            return Candidate.builder()
                    .mode(mode)
                    .label(mode.label())
                    .available(false)
                    .build();
        }
    }

    @Schema(description = "시외 출발편")
    @Builder
    public record Departure(
            /** "KTX 1", "무궁화 1203" 등 사용자에게 보일 이름 */
            String name,
            /** 등급. "KTX"·"무궁화"·항공사명. JSON 키가 class일 수 없어 grade다 */
            String grade,
            String departureAt,
            /**
             * 고속버스는 시간표에 도착시각이 없어 출발+소요로 계산한 값이다.
             * 소요시간마저 없으면 null이다 — 도착 시각을 지어내지 않는다.
             */
            String arrivalAt,
            /** 소요시간(분). 시간표가 주지 않으면 null이다 — 0분으로 두면 즉시 도착이 된다 */
            Integer durationMin,
            /** 항공은 요금 정보가 없어 null이다 */
            Integer fare,
            TransitResDTO.FareConfidence fareConfidence,
            /** 기차만 등급별 요금이 있다. 나머지는 null */
            FareOptions fareOptions,
            /** "최저 요금" 등 이 편이 뽑힌 이유 */
            List<String> labels
    ) {
    }

    @Schema(description = "등급별 요금")
    public record FareOptions(Integer general, Integer special, Integer standing) {
    }
}
