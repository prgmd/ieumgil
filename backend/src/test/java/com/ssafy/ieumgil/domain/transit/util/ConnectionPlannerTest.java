package com.ssafy.ieumgil.domain.transit.util;

import com.ssafy.ieumgil.domain.transit.dto.TransitCandidateResDTO.Departure;
import com.ssafy.ieumgil.domain.transit.dto.TransitCandidateResDTO.TransitMode;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ConnectionPlannerTest {

    @Test
    @DisplayName("첫 편 도착 + 환승 여유 이후 최속 연결편을 붙인다")
    void 연결편을_고른다() {
        var first = List.of(dep("09:12", "10:11"), dep("11:20", "12:19"));
        var second = List.of(dep("10:30", "11:35"), dep("12:05", "13:10"), dep("14:30", "15:35"));

        var out = ConnectionPlanner.connect(first, second, TransitMode.AIR);   // 여유 40분

        // 10:11 + 40 = 10:51 이후 → 12:05
        assertThat(out.get(0).connection().departureAt()).isEqualTo("12:05");
        // 12:19 + 40 = 12:59 이후 → 14:30
        assertThat(out.get(1).connection().departureAt()).isEqualTo("14:30");
    }

    @Test
    @DisplayName("연결편이 없는 첫 편은 후보에서 제외한다 — 탈 수 없는 조합을 보여주지 않는다")
    void 연결_불가_편은_제외한다() {
        var first = List.of(dep("22:00", "23:30"));
        var second = List.of(dep("10:30", "11:35"));

        assertThat(ConnectionPlanner.connect(first, second, TransitMode.AIR)).isEmpty();
    }

    @Test
    @DisplayName("transferMin은 첫 편 도착부터 연결편 출발까지다")
    void 환승_대기를_계산한다() {
        var first = List.of(dep("09:12", "10:11"));
        var second = List.of(dep("12:05", "13:10"));

        var out = ConnectionPlanner.connect(first, second, TransitMode.AIR);

        // 10:11 도착 → 12:05 출발 = 114분
        assertThat(out.get(0).connection().transferMin()).isEqualTo(114);
    }

    @Test
    @DisplayName("여유_시각과_정확히_같은_편도_탈_수_있다")
    void 여유_시각과_정확히_같은_편도_탈_수_있다() {
        var first = List.of(dep("09:00", "10:00"));
        // 10:00 도착 + 10분(기차 여유) = 10:10. 그 경계와 정확히 같은 시각의 편이다.
        var second = List.of(dep("10:10", "11:00"));

        var out = ConnectionPlanner.connect(first, second, TransitMode.TRAIN);

        // 경계는 포함이다 — 여유가 끝나는 순간 도착하는 편도 탈 수 있다. 배타적(>)으로 바뀌면
        // 이 편이 빠져 out이 비게 된다.
        assertThat(out).hasSize(1);
        assertThat(out.get(0).connection().departureAt()).isEqualTo("10:10");
    }

    @Test
    @DisplayName("자정을_넘긴_첫_편은_연결편을_붙이지_않는다")
    void 자정을_넘긴_첫_편은_연결편을_붙이지_않는다() {
        // 23:30 출발 → 05:10 도착(이튿날). 도착 시각만 보면 310분이라 "도착+여유 < 1440" 검사를
        // 그냥 통과한다.
        var first = List.of(dep("23:30", "05:10"));
        // 기준일로 조회한 시간표다 — 06:00 편은 승객이 실제로 내리기 약 18시간 전에 떠났다.
        var second = List.of(dep("06:00", "07:00"));

        var out = ConnectionPlanner.connect(first, second, TransitMode.TRAIN);

        // 이튿날 시간표를 조회하지 않으므로 "연결편 없음"이 정답이다. 검사가 빠지면 out 에
        // dep=06:00, transferMin=50 짜리 탈 수 없는 조합이 하나 남는다.
        assertThat(out).isEmpty();
    }

    private Departure dep(String departureAt, String arrivalAt) {
        return Departure.builder()
                .departureAt(departureAt)
                .arrivalAt(arrivalAt)
                .build();
    }
}
