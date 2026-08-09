package com.ssafy.ieumgil.domain.transit.client;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TransitCacheKeysTest {

    @Test
    @DisplayName("좌표를 소수 4자리로 반올림한다 — 11m 안쪽 차이로 캐시 미스가 나면 안 된다")
    void 좌표를_네_자리로_반올림한다() {
        // 사용자가 지도에서 같은 장소를 두 번 찍으면 소수 6~7자리가 미세하게 다르다
        String first = TransitCacheKeys.route(37.5663012, 126.9779456, 35.1796123, 129.0756789, "TRANSIT");
        String second = TransitCacheKeys.route(37.5663044, 126.9779488, 35.1796101, 129.0756712, "TRANSIT");

        assertThat(first).isEqualTo(second);
        assertThat(first).isEqualTo("transit:route:TRANSIT:37.5663:126.9779:35.1796:129.0757");
    }

    @Test
    @DisplayName("11m보다 크게 다른 좌표는 다른 키다 — 아무 좌표나 같은 답을 주면 안 된다")
    void 충분히_다른_좌표는_다른_키다() {
        String seoul = TransitCacheKeys.route(37.5663, 126.9779, 35.1796, 129.0756, "TRANSIT");
        String nearby = TransitCacheKeys.route(37.5763, 126.9779, 35.1796, 129.0756, "TRANSIT");

        assertThat(seoul).isNotEqualTo(nearby);
    }

    @Test
    @DisplayName("mode가 키에 들어간다 — 빼면 버스 전용 결과가 통합 조회의 답으로 나간다")
    void mode가_키를_가른다() {
        String transit = TransitCacheKeys.route(37.5663, 126.9779, 35.1796, 129.0756, "TRANSIT");
        String bus = TransitCacheKeys.route(37.5663, 126.9779, 35.1796, 129.0756, "BUS");
        String subway = TransitCacheKeys.route(37.5663, 126.9779, 35.1796, 129.0756, "SUBWAY");

        assertThat(transit).isNotEqualTo(bus).isNotEqualTo(subway);
        assertThat(bus).isNotEqualTo(subway);
    }

    @Test
    @DisplayName("출발·도착을 바꾸면 다른 키다 — 역방향 경로는 같은 경로가 아니다")
    void 방향이_키를_가른다() {
        String forward = TransitCacheKeys.route(37.5663, 126.9779, 35.1796, 129.0756, "TRANSIT");
        String backward = TransitCacheKeys.route(35.1796, 129.0756, 37.5663, 126.9779, "TRANSIT");

        assertThat(forward).isNotEqualTo(backward);
    }

    @Test
    @DisplayName("시간표 키는 수단·역 ID로 갈리고 날짜는 없다 — 한 엔트리가 모든 날짜를 커버한다")
    void 시간표_키는_수단과_역_ID로_갈린다() {
        // ODsay 시간표 호출 자체가 날짜를 받지 않고 runDay가 붙은 전체 시간표를 준다 —
        // 요일 필터는 TransitScheduleQueryServiceImpl이 그 뒤에 한다
        assertThat(TransitCacheKeys.schedule("train", 3300128, 3300108))
                .isEqualTo("transit:sched:train:3300128:3300108");
        assertThat(TransitCacheKeys.schedule("bus", 4000057, 4000156))
                .isEqualTo("transit:sched:bus:4000057:4000156");
        // 같은 역 쌍이라도 수단이 다르면 다른 엔트리다
        assertThat(TransitCacheKeys.schedule("train", 3300128, 3300108))
                .isNotEqualTo(TransitCacheKeys.schedule("bus", 3300128, 3300108));
    }

    @Test
    @DisplayName("음수 좌표에서도 지수 표기나 -0.0이 섞이지 않는다")
    void 음수_좌표도_같은_형식이다() {
        String key = TransitCacheKeys.route(-0.00001, -33.8688, 151.2093, 0.0, "TRANSIT");

        assertThat(key).doesNotContain("E").doesNotContain("e-");
        assertThat(key).isEqualTo("transit:route:TRANSIT:-0.0000:-33.8688:151.2093:0.0000");
    }
}
