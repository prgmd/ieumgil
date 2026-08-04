package com.ssafy.ieumgil.domain.transit.service;

import com.ssafy.ieumgil.domain.transit.dto.TransitCandidateResDTO.Departure;
import com.ssafy.ieumgil.domain.transit.dto.TransitResDTO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DepartureSelectorTest {

    @Test
    @DisplayName("시각순 2편 + 최저 요금 1편을 고른다")
    void 시각순_둘과_최저가_하나를_고른다() {
        List<Departure> all = List.of(
                departure("KTX 1", "16:00", 59800),
                departure("KTX 15", "16:30", 59800),
                departure("KTX 21", "17:00", 59800),
                departure("무궁화 1203", "18:10", 28600));

        List<Departure> selected = DepartureSelector.selectThree(all, true);

        assertThat(selected).hasSize(3);
        assertThat(selected.get(0).name()).isEqualTo("KTX 1");
        assertThat(selected.get(1).name()).isEqualTo("KTX 15");
        assertThat(selected.get(2).name()).isEqualTo("무궁화 1203");
        assertThat(selected.get(2).labels()).contains("최저 요금");
    }

    @Test
    @DisplayName("가장 빠른 편이 곧 최저가면 시각순으로 채운다")
    void 최저가가_겹치면_시각순으로_채운다() {
        List<Departure> all = List.of(
                departure("무궁화 1201", "16:00", 28600),
                departure("KTX 15", "16:30", 59800),
                departure("KTX 21", "17:00", 59800));

        List<Departure> selected = DepartureSelector.selectThree(all, true);

        assertThat(selected).extracting(Departure::name)
                .containsExactly("무궁화 1201", "KTX 15", "KTX 21");
    }

    @Test
    @DisplayName("요금을 모르는 수단은 시각순 3편이다")
    void 요금을_모르면_시각순_셋이다() {
        List<Departure> flights = List.of(
                departure("ZE711", "07:40", null),
                departure("TW843", "08:35", null),
                departure("KE1234", "09:10", null),
                departure("OZ8901", "10:00", null));

        List<Departure> selected = DepartureSelector.selectThree(flights, false);

        assertThat(selected).extracting(Departure::name).containsExactly("ZE711", "TW843", "KE1234");
        assertThat(selected).allSatisfy(d -> assertThat(d.labels()).isEmpty());
    }

    @Test
    @DisplayName("요금을 모르는 편은 최저가로 뽑지 않는다 — null을 0원으로 읽으면 늘 최저가가 된다")
    void 요금을_모르는_편은_최저가가_아니다() {
        List<Departure> all = List.of(
                departure("고속버스 06:00", "16:00", 39700),
                departure("고속버스 06:30", "16:30", 41000),
                departure("고속버스 07:00", "17:00", null),
                departure("고속버스 07:30", "17:30", null));

        List<Departure> selected = DepartureSelector.selectThree(all, true);

        assertThat(selected).hasSize(3);
        assertThat(selected).allSatisfy(d -> assertThat(d.labels()).doesNotContain("최저 요금"));
        // 최저가 축을 쓸 수 없으면 남은 자리는 시각순으로 채운다
        assertThat(selected.get(2).name()).isEqualTo("고속버스 07:00");
    }

    @Test
    @DisplayName("3편보다 적으면 있는 만큼만 준다")
    void 편이_적으면_있는_만큼_준다() {
        List<Departure> all = List.of(departure("KTX 1", "16:00", 59800));

        assertThat(DepartureSelector.selectThree(all, true)).hasSize(1);
    }

    @Test
    @DisplayName("편이 없으면 빈 목록이다")
    void 편이_없으면_빈_목록이다() {
        assertThat(DepartureSelector.selectThree(List.of(), true)).isEmpty();
    }

    private Departure departure(String name, String departureAt, Integer fare) {
        return Departure.builder()
                .name(name)
                .grade(name.split(" ")[0])
                .departureAt(departureAt)
                .arrivalAt("23:59")
                .durationMin(157)
                .fare(fare)
                .fareConfidence(fare == null
                        ? TransitResDTO.FareConfidence.UNKNOWN
                        : TransitResDTO.FareConfidence.CONFIRMED)
                .labels(List.of())
                .build();
    }
}
