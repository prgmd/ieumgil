package com.ssafy.ieumgil.domain.transit.service;

import com.ssafy.ieumgil.domain.block.entity.Block;
import com.ssafy.ieumgil.domain.transit.dto.OdsayRouteResponse;
import com.ssafy.ieumgil.domain.transit.dto.TransitCandidateResDTO.Candidate;
import com.ssafy.ieumgil.domain.transit.dto.TransitCandidateResDTO.CandidateStatus;
import com.ssafy.ieumgil.domain.transit.dto.TransitCandidateResDTO.TransitMode;
import com.ssafy.ieumgil.domain.transit.dto.TransitLegResDTO;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 교통 후보 계산의 1단·2단이 공유하는 값 타입과 정책.
 *
 * <p>{@code TransitCandidateServiceImpl}(1단·오케스트레이션)과 {@link IntercityCandidateAssembler}
 * (2단·시외 door-to-door 조립)이 같은 값 타입({@link Pair}·{@link Leg}·{@link AccessLegs}·
 * {@link RoadResult})과 기본 수단 선택 규칙({@link #defaultModeOf})을 쓴다. 이 타입들이 서비스의
 * 중첩 타입으로 남아 있으면 조립기가 서비스를 import하고 서비스가 조립기를 생성해 컴파일 순환이
 * 생긴다 — 중립 위치인 이 홀더로 빼 의존을 한 방향(서비스 → 조립기)으로 만든다.
 */
final class TransitRouteModels {

    private TransitRouteModels() {
    }

    /**
     * ODsay pathType — 11 기차, 12 고속버스, 13 항공, 14 해운, 20 복합.
     *
     * <p>해운(14)을 빼면 안 된다. 시외 경로는 {@code payment}도 환승 수도 주지 않는데,
     * {@link TransitRouteSelector#transferCount}가 null을 0으로 읽어 "환승 최소" 축을 이겨 버린다.
     */
    static final Set<Integer> INTERCITY_PATH_TYPES = Set.of(11, 12, 13, 14, 20);

    /**
     * 앞에서부터 살아있는 첫 후보가 기본이다. 전부 실패했으면 null — 프론트가 그 구간만 비워 둔다.
     *
     * <p>탈 수 있는 편이 없는 시외 후보는 건너뛴다. 조회는 성공했으니 {@code status=OK}지만
     * 고를 편이 없는 수단을 기본으로 내밀 수는 없다.
     *
     * <p>중립 위치에 둔 이유는 {@code TransitCandidateServiceImpl}(시내)과
     * {@link IntercityCandidateAssembler}(시외)가 같은 규칙으로 기본 수단을 고르기 때문이다.
     */
    static TransitMode defaultModeOf(List<Candidate> candidates) {
        return candidates.stream()
                .filter(candidate -> candidate.status() == CandidateStatus.OK)
                .filter(candidate -> candidate.departures() == null || !candidate.departures().isEmpty())
                .map(Candidate::mode)
                .findFirst()
                .orElse(null);
    }

    /**
     * 응답 구간 하나에 대응하는 블록 쌍. 좌표가 같아도 서로 다른 블록이면 별개의 구간이다.
     *
     * <p>package-private인 이유는 {@code TransitCandidateServiceImplTest}가 {@code accessLegsOf}를
     * 직접 부르며 이 타입을 써야 하기 때문이다.
     */
    record Pair(Block from, Block to) {
    }

    /**
     * 외부 조회의 단위. 블록 id가 아니라 좌표로 잡는다 — 좌표가 같으면 답도 같으므로
     * 서로 다른 블록이어도 호출을 한 번으로 줄일 수 있다. 방향은 구분한다(대중교통 경로는 대칭이 아니다).
     */
    record Leg(double fromLat, double fromLng, double toLat, double toLng) {
    }

    /**
     * 접근·이탈 경로({@code IntercityCandidateAssembler#accessLegsOf}의 결과).
     *
     * <p>{@code accessMin}·{@code egressMin}이 int인 이유: 이 레코드가 존재한다는 것 자체가 두
     * 호출이 모두 성공했다는 뜻이다 — 그 안에서는 0으로 추측할 자리가 없다. {@code accessFare}·
     * {@code egressFare}는 Integer다 — 시내 경로의 {@code payment} 자체가 nullable이라 이 값도 그렇다.
     *
     * <p>package-private인 이유는 {@code TransitCandidateServiceImplTest}가 이 타입을 직접 쓰기 때문이다.
     */
    record AccessLegs(
            List<TransitLegResDTO.Leg> access, int accessMin, Integer accessFare,
            List<TransitLegResDTO.Leg> egress, int egressMin, Integer egressFare) {
    }

    /**
     * 1단(병렬) 결과.
     *
     * <p>{@code paths}를 그대로 들고 있는 이유: 시외 판정({@code pathType})·시내 후보 선정·
     * 도서 목적지 판정이 모두 원본을 봐야 한다. 미리 평탄화하면 2단에서 다시 호출하게 된다.
     *
     * @param noRoute ODsay가 "경로가 없다"고 답했는지({@code OdsayNoRouteException}).
     *                {@code paths}가 비어 있다는 사실만으로는 조회 실패와 구분되지 않아 따로 든다.
     */
    record RoadResult(
            List<OdsayRouteResponse.Path> paths,
            boolean transitRequested,
            List<Candidate> roadCandidates,
            boolean noRoute,
            /** ODsay가 700m 이내라 경로를 주지 않았다 — 걸어갈 거리다({@link TransitRouteLookup.RouteLookup}) */
            boolean tooClose
    ) {

        /**
         * 경로 하나라도 시외면 시외 구간이다.
         *
         * <p>이 판정이 곧 시내 선정기의 방어선이다 — 시외 경로가 섞인 구간은 통째로 시외로 가고
         * {@code citySegment}에 닿지 않는다. 섞인 목록을 선정기에 넣으면 요금 없는 시외 경로가
         * "환승 최소" 축을 환승 0으로 이겨 버린다.
         */
        boolean isIntercity() {
            return intercityPath().isPresent();
        }

        String firstStartStation() {
            return intercityPath().map(path -> path.info().firstStartStation()).orElse(null);
        }

        String lastEndStation() {
            return intercityPath().map(path -> path.info().lastEndStation()).orElse(null);
        }

        private Optional<OdsayRouteResponse.Path> intercityPath() {
            return paths.stream()
                    .filter(path -> INTERCITY_PATH_TYPES.contains(path.pathType()))
                    .findFirst();
        }
    }
}
