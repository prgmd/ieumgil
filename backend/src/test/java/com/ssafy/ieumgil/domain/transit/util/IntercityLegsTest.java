package com.ssafy.ieumgil.domain.transit.util;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ssafy.ieumgil.domain.transit.dto.OdsayRouteResponse;
import com.ssafy.ieumgil.domain.transit.dto.OdsayRouteResponse.Info;
import com.ssafy.ieumgil.domain.transit.dto.OdsayRouteResponse.Path;
import com.ssafy.ieumgil.domain.transit.dto.OdsayRouteResponse.SubPath;
import com.ssafy.ieumgil.domain.transit.dto.TransitCandidateResDTO.TransitMode;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class IntercityLegsTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private OdsayRouteResponse readFixture(String name) throws IOException {
        try (InputStream in = getClass().getResourceAsStream("/odsay/" + name)) {
            return mapper.readValue(in, OdsayRouteResponse.class);
        }
    }

    @Test
    @DisplayName("직통 경로는 시외 leg 1개, 수단은 ID 대역으로 정한다")
    void 직통_경로() throws IOException {
        // 서울역(3300128) → 부산역(3300108), odsay-intercity.json 실측 픽스처
        OdsayRouteResponse r = readFixture("odsay-intercity.json");
        Path path = r.result().path().stream()
                .filter(p -> p.pathType() == 11)
                .filter(p -> p.subPath().get(0).startID() == 3300128 && p.subPath().get(0).endID() == 3300108)
                .findFirst().orElseThrow();

        IntercityLegs legs = IntercityLegs.of(path).orElseThrow();

        assertThat(legs.legs()).hasSize(1);
        assertThat(legs.isTransfer()).isFalse();
        assertThat(legs.mode()).isEqualTo(TransitMode.TRAIN);
        assertThat(legs.boardingPoint().x()).isEqualTo(126.970681);
        assertThat(legs.boardingPoint().y()).isEqualTo(37.554522);
    }

    @Test
    @DisplayName("[실측 71%] 환승 경로는 시외 leg 2개다")
    void 환승_경로() {
        // pathType 11: 용산(3300197)→광주송정(3300140) 후 환승
        Path path = trainPath(2, 218,
                trainLeg(3300197, 3300140, 126.9673, 37.5299, 126.7936, 35.1595),
                trainLeg(3300140, 3300108, 126.7936, 35.1595, 129.0403, 35.1150));

        IntercityLegs legs = IntercityLegs.of(path).orElseThrow();

        assertThat(legs.legs()).hasSize(2);
        assertThat(legs.isTransfer()).isTrue();
        // 마지막 leg 기준이지만 기차 환승이라 첫 leg와 같은 대역이다 — pathType 11은 늘 그렇다
        assertThat(legs.mode()).isEqualTo(TransitMode.TRAIN);
        // boardingPoint는 첫 leg의 승차 좌표, alightingPoint는 마지막 leg의 하차 좌표다 —
        // 둘을 뒤바꿔도(legs.get(0) ↔ legs.get(size-1)) 위 assertion은 안 걸린다
        assertThat(legs.boardingPoint()).isEqualTo(new IntercityLegs.Point(126.9673, 37.5299));
        assertThat(legs.alightingPoint()).isEqualTo(new IntercityLegs.Point(129.0403, 35.1150));
    }

    @Test
    @DisplayName("복합 경로는 수단이 섞인다 — 대표는 목적지에 도달하는 마지막 leg다")
    void 복합_경로() {
        // pathType 20: 기차 오송(3300302)→광주송정 + 항공 광주공항(3500008)→제주(3500003).
        // 첫 leg 기준으로 정하면 "제주행 기차"가 되어 존재하지 않는 이동수단을 제안하게 된다 —
        // 제주에 닿는 수단은 항공이다.
        Path path = new Path(20,
                new Info(220, null, null, 400000, null, null, null, "오송", "제주", null, 1),
                List.of(
                        trainLeg(3300302, 3300140, 127.3255, 36.6203, 126.7936, 35.1595),
                        airLeg(3500008, 3500003, 126.4113, 35.1264, 126.4930, 33.5107)));

        IntercityLegs legs = IntercityLegs.of(path).orElseThrow();

        assertThat(legs.mode()).isEqualTo(TransitMode.AIR);
        // 승·하차 지점은 대표 수단과 무관하다 — 승차는 첫 leg, 하차는 마지막 leg다
        assertThat(legs.boardingPoint()).isEqualTo(new IntercityLegs.Point(127.3255, 36.6203));
        assertThat(legs.alightingPoint()).isEqualTo(new IntercityLegs.Point(126.4930, 33.5107));
        // 첫 leg의 수단은 여전히 기차다 — 시간표 조회는 leg마다 자기 수단으로 가야 한다
        assertThat(StationIdBands.modeOf(legs.legs().get(0).startID())).contains(TransitMode.TRAIN);
    }

    @Test
    @DisplayName("[실측] 단일 수단 경로(pathType 11·12·13)는 마지막 leg 기준으로도 대표 수단이 그대로다")
    void 단일_수단_경로는_대표_수단이_바뀌지_않는다() throws IOException {
        // 실측(2026-08-05, 서울→부산 19 · 서울→여수 22 · 대구→서울 18 · 안동→목포 18 · 제천→포항 20 ·
        // 서울→안동 9 = 106경로): pathType 11·12·13은 leg가 2개여도 첫 leg와 마지막 leg의 역 ID
        // 대역이 항상 같았다. 대역이 섞이는 것은 pathType 20뿐이다.
        Path trainTransfer = new Path(11,   // 안동(3300164)→서울역 환승 → 목포(3300087). 둘 다 3300xxx
                new Info(330, null, null, 400000, null, null, null, "안동", "목포", 60000, 2),
                List.of(
                        trainLeg(3300164, 3300128, 128.7294, 36.5684, 126.9707, 37.5546),
                        trainLeg(3300128, 3300087, 126.9707, 37.5546, 126.3922, 34.8118)));
        Path busTransfer = new Path(12,     // 센트럴시티(4000059)→환승(4000134) → 여수(4000064)
                new Info(300, null, null, 380000, null, null, null, "서울", "여수", 40000, 2),
                List.of(
                        busLeg(4000059, 4000134, 127.0058, 37.5057, 127.1500, 35.9500),
                        busLeg(4000134, 4000064, 127.1500, 35.9500, 127.6622, 34.7604)));

        assertThat(IntercityLegs.of(trainTransfer).orElseThrow().mode()).isEqualTo(TransitMode.TRAIN);
        assertThat(IntercityLegs.of(busTransfer).orElseThrow().mode()).isEqualTo(TransitMode.EXPRESS_BUS);

        // 실측 픽스처의 시외 경로 전부에 대해 "대표 수단 == 첫 leg 대역"이 유지되는지 본다 —
        // odsay-intercity.json에는 pathType 11·12·13만 있다(복합 20은 없다)
        for (Path path : readFixture("odsay-intercity.json").result().path()) {
            if (path.pathType() != 11 && path.pathType() != 12 && path.pathType() != 13) {
                continue;
            }
            assertThat(IntercityLegs.of(path).orElseThrow().mode())
                    .isEqualTo(StationIdBands.modeOf(path.subPath().get(0).startID()).orElseThrow());
        }
    }

    @Test
    @DisplayName("첫 leg가 항공(tt7·3500xxx)이면 AIR — 옛 trafficType 매핑(7→FERRY)으로 되돌리면 실패한다")
    void 첫_leg가_항공이면_AIR() {
        Path path = new Path(13,
                new Info(65, null, null, 500000, null, null, null, "김포", "김해", null, 1),
                List.of(airLeg(3500001, 3500004, 126.8027, 37.5592, 128.9382, 35.1795)));

        IntercityLegs legs = IntercityLegs.of(path).orElseThrow();

        assertThat(legs.mode()).isEqualTo(TransitMode.AIR);
    }

    @Test
    @DisplayName("첫 leg가 시외버스(tt6·4000xxx)면 EXPRESS_BUS — 옛 trafficType 매핑(6→AIR)으로 되돌리면 실패한다")
    void 첫_leg가_시외버스면_EXPRESS_BUS() {
        Path path = new Path(12,
                new Info(140, null, null, 300000, null, null, null, "서부정류장", "광주종합", null, 1),
                List.of(busLeg(4000057, 4000156, 127.0058, 37.5057, 126.8515, 35.1595)));

        IntercityLegs legs = IntercityLegs.of(path).orElseThrow();

        assertThat(legs.mode()).isEqualTo(TransitMode.EXPRESS_BUS);
    }

    @Test
    @DisplayName("수단별 대표 경로는 환승 적은 것 → 동률이면 총시간 짧은 것")
    void 대표_경로_선택() {
        Path directButSlow = trainPath(1, 300, trainLeg(3300128, 3300108, 126.97, 37.55, 129.04, 35.11));
        Path transfer = trainPath(2, 200,
                trainLeg(3300128, 3300140, 126.97, 37.55, 126.79, 35.16),
                trainLeg(3300140, 3300108, 126.79, 35.16, 129.04, 35.11));
        Path fasterDirect = trainPath(1, 250, trainLeg(3300177, 3300108, 126.90, 37.51, 129.04, 35.11));
        Path slowerDirect = trainPath(1, 280, trainLeg(3300333, 3300108, 127.10, 37.48, 129.04, 35.11));

        Map<TransitMode, IntercityLegs> picked = IntercityLegs.pick(
                List.of(directButSlow, transfer, fasterDirect, slowerDirect));

        // transitCount 1이 2보다 우선이고, 1끼리는 totalTime 250이 300·280보다 짧다
        assertThat(picked.get(TransitMode.TRAIN).path()).isSameAs(fasterDirect);
    }

    @Test
    @DisplayName("ID 대역을 모르는 leg만 있으면 empty")
    void 모르는_수단은_empty다() {
        Path path = trainPath(1, 40, trainLeg(106171, 106172, 127.0, 37.5, 127.1, 37.6));

        Optional<IntercityLegs> legs = IntercityLegs.of(path);

        assertThat(legs).isEmpty();
    }

    private Path trainPath(int transitCount, int totalTime, SubPath... legs) {
        return new Path(11,
                new Info(totalTime, null, null, 300000, null, null, null, "서울", "부산", null, transitCount),
                List.of(legs));
    }

    private SubPath trainLeg(int startId, int endId, double startX, double startY, double endX, double endY) {
        return new SubPath(4, 100, 200000, "출발역", "도착역", null,
                startId, endId, startX, startY, endX, endY,
                59800, null, null, null, null, "KTX");
    }

    private SubPath airLeg(int startId, int endId, double startX, double startY, double endX, double endY) {
        return new SubPath(7, 80, 400000, "출발공항", "도착공항", null,
                startId, endId, startX, startY, endX, endY,
                120000, null, null, null, null, null);
    }

    private SubPath busLeg(int startId, int endId, double startX, double startY, double endX, double endY) {
        return new SubPath(6, 140, 300000, "출발터미널", "도착터미널", null,
                startId, endId, startX, startY, endX, endY,
                20900, null, null, null, null, null);
    }
}
