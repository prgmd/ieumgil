package com.ssafy.ieumgil.domain.transit.dto;

import com.ssafy.ieumgil.domain.transit.dto.TransitCandidateResDTO.Candidate;
import com.ssafy.ieumgil.domain.transit.dto.TransitCandidateResDTO.TransitMode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TransitCandidateResDTOTest {

    @Test
    @DisplayName("unavailable은 mode·label만 채우고 나머지를 비운다")
    void 조회_실패_후보는_값이_비어_있다() {
        Candidate candidate = Candidate.unavailable(TransitMode.TRAIN);

        assertThat(candidate.mode()).isEqualTo(TransitMode.TRAIN);
        assertThat(candidate.label()).isEqualTo("기차");
        assertThat(candidate.available()).isFalse();
        assertThat(candidate.durationMin()).isNull();
        assertThat(candidate.fare()).isNull();
        assertThat(candidate.legs()).isNull();
        assertThat(candidate.departures()).isNull();
    }

    @Test
    @DisplayName("시외 수단 라벨은 한글이다")
    void 시외_수단_라벨() {
        assertThat(TransitMode.TRAIN.label()).isEqualTo("기차");
        assertThat(TransitMode.EXPRESS_BUS.label()).isEqualTo("고속·시외버스");
        assertThat(TransitMode.AIR.label()).isEqualTo("항공");
    }

    @Test
    @DisplayName("출발편은 등급별 요금을 함께 담는다")
    void 출발편_요금_선택지() {
        TransitCandidateResDTO.Departure departure = TransitCandidateResDTO.Departure.builder()
                .name("KTX 1")
                .grade("KTX")
                .departureAt("16:00")
                .arrivalAt("18:37")
                .durationMin(157)
                .fare(59800)
                .fareConfidence(TransitResDTO.FareConfidence.CONFIRMED)
                .fareOptions(new TransitCandidateResDTO.FareOptions(59800, 83700, 50830))
                .labels(List.of())
                .build();

        assertThat(departure.grade()).isEqualTo("KTX");
        assertThat(departure.fareOptions().standing()).isEqualTo(50830);
    }
}
