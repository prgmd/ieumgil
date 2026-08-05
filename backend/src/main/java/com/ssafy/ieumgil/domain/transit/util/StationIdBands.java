package com.ssafy.ieumgil.domain.transit.util;

import java.util.Optional;

import com.ssafy.ieumgil.domain.transit.dto.TransitCandidateResDTO.TransitMode;

/**
 * ODsay station ID 대역으로 수단을 판별한다.
 *
 * <p>{@code trafficType}을 쓰지 않는 이유: 실측에서 6·7이 뒤집혀 있었다(6=시외버스, 7=항공).
 * ID 대역은 1,700여 leg 관측에서 3300(기차)·3500(공항)이 100% 갈렸고, 그 ID가
 * 시간표 API에 그대로 들어가는 값이라 판별자로도 가장 신뢰할 만하다.
 *
 * <p>버스 계열 대역(3400·3600·4000)은 실측에서 관측된 범위일 뿐, 다른 버스 대역이
 * 없다는 것을 증명하지는 못한다. 그래도 미관측 ID를 "버스가 아니면 뭐겠어"로 추측하지
 * 않고 {@link Optional#empty()}를 반환한다 — 시내 정류장 ID를 버스터미널로 오판하는
 * 것보다, 판별 실패를 호출자가 로그로 남기고 건너뛰게 하는 쪽이 안전하다.
 */
public final class StationIdBands {

    private static final int TRAIN_MIN = 3_300_000, TRAIN_MAX = 3_399_999;
    private static final int AIR_MIN = 3_500_000, AIR_MAX = 3_599_999;
    /** 실측 관측 대역 — 3400·3600·4000. tt5·tt6이 섞이며 같은 시간표 API를 쓴다 */
    private static final int[][] BUS_BANDS = {
            {3_400_000, 3_499_999}, {3_600_000, 3_699_999}, {4_000_000, 4_099_999}};

    private StationIdBands() {
    }

    public static Optional<TransitMode> modeOf(int stationId) {
        if (in(stationId, TRAIN_MIN, TRAIN_MAX)) return Optional.of(TransitMode.TRAIN);
        if (isAir(stationId)) return Optional.of(TransitMode.AIR);
        for (int[] b : BUS_BANDS) {
            if (in(stationId, b[0], b[1])) return Optional.of(TransitMode.EXPRESS_BUS);
        }
        return Optional.empty();
    }

    /**
     * 공항 대역(3500xxx)인지. 육로 판정({@link LandReachability})이 {@code trafficType} 대신 이
     * 대역을 쓴다 — 그 판정이 {@code trafficType}으로 항공을 가리다가 시외버스를 항공으로 오분류해
     * 폐기됐다.
     */
    public static boolean isAir(int stationId) {
        return in(stationId, AIR_MIN, AIR_MAX);
    }

    private static boolean in(int v, int lo, int hi) {
        return v >= lo && v <= hi;
    }
}
