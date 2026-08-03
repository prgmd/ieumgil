package com.ssafy.ieumgil.domain.transit.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;

import java.util.List;

public class TransitCandidateResDTO {

    /**
     * 이동수단.
     *
     * <p>{@code TRANSIT}은 버스·지하철을 통합한 대중교통이다. 둘을 따로 내지 않는 이유는
     * 사용자가 고르는 단위가 "대중교통이냐 택시냐"이지 "버스냐 지하철이냐"가 아니기 때문이다.
     */
    public enum TransitMode {
        TRANSIT("대중교통"),
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
            Integer distanceM
    ) {

        /** 조회 실패 — mode·label만 채우고 나머지 값(durationMin/fare/intervalMin/distanceM)은 비운 채 available만 false다 */
        public static Candidate unavailable(TransitMode mode) {
            return Candidate.builder()
                    .mode(mode)
                    .label(mode.label())
                    .available(false)
                    .build();
        }
    }
}
