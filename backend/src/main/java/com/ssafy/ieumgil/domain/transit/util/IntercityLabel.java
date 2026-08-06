package com.ssafy.ieumgil.domain.transit.util;

import com.ssafy.ieumgil.domain.transit.dto.OdsayRouteResponse.SubPath;
import com.ssafy.ieumgil.domain.transit.dto.TransitCandidateResDTO.TransitMode;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 시외 후보의 사용자 표시 이름. leg마다 자기 수단 이름을 leg <b>순서대로</b> {@code +}로 잇는다.
 *
 * <p>대표 수단({@link IntercityLegs#mode})만으로 이름을 붙이면 복합 경로의 앞 구간이 사라진다 —
 * 동서울터미널→새말정류소(버스) + 원주공항→제주공항(항공) 경로는 대표 수단이 항공이라 "항공"으로
 * 나가는데, 사용자는 버스를 먼저 타야 한다는 사실을 화면에서 알 수 없다. 그래서
 * {@code TransitMode}(버킷·아이콘 키)는 그대로 두고 표시 이름만 "시외버스+항공"으로 낸다.
 *
 * <p>수단은 {@code trafficType}이 아니라 출발역 ID 대역({@link StationIdBands})으로 판별한다 —
 * 실측에서 6(시외버스)·7(항공)이 뒤집혀 있었다.
 *
 * <p>단일 수단 경로는 대표 수단 이름 그대로다. leg가 2개여도 대역이 같으면
 * ({@code pathType 11} 기차 환승·{@code 12} 버스 환승 — 실측 106경로에서 첫 leg와 마지막 leg의
 * 대역이 항상 같았다) "기차+기차"가 아니라 "기차"다. 그건 정보가 아니라 잡음이다.
 * 실제로 수단이 섞이는 것은 {@code pathType 20}뿐이다.
 */
public final class IntercityLabel {

    private static final String SEPARATOR = "+";

    private IntercityLabel() {
    }

    /**
     * @param legs           시외 leg({@link IntercityLegs#legs})
     * @param representative 대표 수단. 단일 수단 경로이거나 어느 leg의 대역을 판별할 수 없으면
     *                       이 수단의 이름을 그대로 쓴다 — 없는 이름을 지어내지 않는다
     */
    public static String of(List<SubPath> legs, TransitMode representative) {
        return modesOf(legs)
                .filter(modes -> modes.stream().distinct().count() > 1)
                .map(modes -> modes.stream().map(TransitMode::label).collect(Collectors.joining(SEPARATOR)))
                .orElseGet(representative::label);
    }

    /** leg마다 자기 출발역 대역으로 수단을 판별한다. 하나라도 판별하지 못하면 empty — 추측하지 않는다 */
    private static Optional<List<TransitMode>> modesOf(List<SubPath> legs) {
        if (legs == null || legs.isEmpty()) {
            return Optional.empty();
        }
        List<TransitMode> modes = new ArrayList<>();
        for (SubPath leg : legs) {
            Optional<TransitMode> mode = leg.startID() == null
                    ? Optional.empty()
                    : StationIdBands.modeOf(leg.startID());
            if (mode.isEmpty()) {
                return Optional.empty();
            }
            modes.add(mode.get());
        }
        return Optional.of(modes);
    }
}
