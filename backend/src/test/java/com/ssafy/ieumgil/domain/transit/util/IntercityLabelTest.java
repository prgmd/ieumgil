package com.ssafy.ieumgil.domain.transit.util;

import com.ssafy.ieumgil.domain.transit.dto.OdsayRouteResponse.SubPath;
import com.ssafy.ieumgil.domain.transit.dto.TransitCandidateResDTO.TransitMode;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class IntercityLabelTest {

    @Test
    @DisplayName("복합 경로는 leg 순서대로 수단 이름을 '+'로 잇는다 — 대표 수단만으로 이름 붙이면 앞 구간이 사라진다")
    void 복합_경로는_수단을_이어_붙인다() {
        // 실측 서울→제주: 동서울종합터미널(4000035) → 새말정류소(3601154) + 원주공항(3500014) → 제주공항
        String busThenAir = IntercityLabel.of(
                List.of(leg(6, 4000035, 3601154), leg(7, 3500014, 3500003)), TransitMode.AIR);
        // 실측 서울→제주: 오송(3300302) → 광주송정 + 광주공항(3500008) → 제주공항
        String trainThenAir = IntercityLabel.of(
                List.of(leg(4, 3300302, 3300140), leg(7, 3500008, 3500003)), TransitMode.AIR);

        // 구분자는 공백 없는 '+'다
        assertThat(busThenAir).isEqualTo("고속·시외버스+항공");
        assertThat(trainThenAir).isEqualTo("기차+항공");
    }

    @Test
    @DisplayName("단일 leg 경로는 대표 수단 이름 그대로다")
    void 단일_leg는_대표_수단_이름이다() {
        // 실측 서울→제주 pathType 13: 김포국제공항(3500001) → 제주국제공항(3500003)
        String label = IntercityLabel.of(List.of(leg(7, 3500001, 3500003)), TransitMode.AIR);

        assertThat(label).isEqualTo("항공");
    }

    @Test
    @DisplayName("같은 수단 환승(pathType 11·12)은 '기차+기차'가 아니라 단일 이름이다 — 그건 정보가 아니라 잡음이다")
    void 같은_수단_환승은_단일_이름이다() {
        // pathType 11: 안동(3300164) → 서울역 환승 → 목포(3300087). 둘 다 3300xxx 대역이다
        String trainTransfer = IntercityLabel.of(
                List.of(leg(4, 3300164, 3300128), leg(4, 3300128, 3300087)), TransitMode.TRAIN);
        // pathType 12: 센트럴시티(4000059) → 환승(4000134) → 여수(4000064)
        String busTransfer = IntercityLabel.of(
                List.of(leg(5, 4000059, 4000134), leg(6, 4000134, 4000064)), TransitMode.EXPRESS_BUS);

        assertThat(trainTransfer).isEqualTo("기차");
        assertThat(busTransfer).isEqualTo("고속·시외버스");
    }

    @Test
    @DisplayName("수단은 trafficType이 아니라 역 ID 대역으로 판별한다 — 실측에서 tt6·tt7이 뒤집혀 있었다")
    void 수단은_trafficType이_아니라_대역으로_판별한다() {
        // 첫 leg의 trafficType은 7(옛 매핑이 항공·해운으로 읽던 값)인데 역 ID는 기차 대역(3300xxx)이고,
        // 두 번째 leg는 trafficType 6(옛 매핑이 항공으로 읽던 값)인데 역 ID는 공항 대역(3500xxx)이다.
        // trafficType으로 이름을 붙이면 두 수단이 서로 뒤바뀌어 "항공+고속·시외버스"가 된다.
        String label = IntercityLabel.of(
                List.of(leg(7, 3300128, 3300140), leg(6, 3500008, 3500003)), TransitMode.AIR);

        assertThat(label).isEqualTo("기차+항공");
    }

    @Test
    @DisplayName("어느 leg의 대역이라도 모르면 이름을 지어내지 않고 대표 수단 이름으로 물러난다")
    void 대역을_모르면_대표_수단_이름으로_물러난다() {
        // 두 번째 leg의 역 ID가 알려진 대역 밖이다
        String unknownBand = IntercityLabel.of(
                List.of(leg(4, 3300128, 3300140), leg(7, 9_999_999, 9_999_998)), TransitMode.AIR);
        // 역 ID 자체가 없는 leg(시내 leg 등)도 같다
        String noStationId = IntercityLabel.of(
                List.of(leg(4, 3300128, 3300140), noIdLeg()), TransitMode.AIR);

        assertThat(unknownBand).isEqualTo("항공");
        assertThat(noStationId).isEqualTo("항공");
    }

    @Test
    @DisplayName("leg 목록이 비었거나 null이면 대표 수단 이름이다")
    void leg가_없으면_대표_수단_이름이다() {
        assertThat(IntercityLabel.of(List.of(), TransitMode.TRAIN)).isEqualTo("기차");
        assertThat(IntercityLabel.of(null, TransitMode.TRAIN)).isEqualTo("기차");
    }

    private SubPath leg(int trafficType, Integer startId, Integer endId) {
        return new SubPath(trafficType, 100, 200000, "출발", "도착", null,
                startId, endId, 127.0, 37.5, 127.1, 37.6,
                null, null, null, null, null, null);
    }

    /** 역 ID가 없는 leg. ODsay 시내 도보 leg가 이 모양이다 */
    private SubPath noIdLeg() {
        return new SubPath(3, 10, 800, "출발", "도착", null);
    }
}
