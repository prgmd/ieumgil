package com.ssafy.ieumgil.domain.transit.dto;

import com.ssafy.ieumgil.domain.transit.dto.TransitCandidateResDTO.Candidate;
import com.ssafy.ieumgil.domain.transit.dto.TransitCandidateResDTO.CandidateStatus;
import com.ssafy.ieumgil.domain.transit.dto.TransitCandidateResDTO.TransitMode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TransitCandidateResDTOTest {

    @Test
    @DisplayName("status는 네 값이다 — NO_ROUTE가 LOOKUP_FAILED와 구별된다")
    void status는_네_값이다() {
        assertThat(CandidateStatus.values()).containsExactly(
                CandidateStatus.OK, CandidateStatus.NO_SERVICE,
                CandidateStatus.NO_ROUTE, CandidateStatus.LOOKUP_FAILED);
    }

    @Test
    @DisplayName("lookupFailed는 mode·label만 채우고 나머지를 비운다")
    void 조회_실패_후보는_값이_비어_있다() {
        Candidate candidate = Candidate.lookupFailed(TransitMode.TRAIN);

        assertThat(candidate.mode()).isEqualTo(TransitMode.TRAIN);
        assertThat(candidate.label()).isEqualTo("기차");
        assertThat(candidate.status()).isEqualTo(CandidateStatus.LOOKUP_FAILED);
        assertThat(candidate.durationMin()).isNull();
        assertThat(candidate.fare()).isNull();
        assertThat(candidate.legs()).isNull();
        assertThat(candidate.departures()).isNull();
    }

    @Test
    @DisplayName("noRoute는 mode·label만 채우고 나머지를 비운다")
    void 경로_없음_후보는_값이_비어_있다() {
        Candidate candidate = Candidate.noRoute(TransitMode.CAR);

        assertThat(candidate.mode()).isEqualTo(TransitMode.CAR);
        assertThat(candidate.label()).isEqualTo("자차");
        assertThat(candidate.status()).isEqualTo(CandidateStatus.NO_ROUTE);
        assertThat(candidate.durationMin()).isNull();
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
