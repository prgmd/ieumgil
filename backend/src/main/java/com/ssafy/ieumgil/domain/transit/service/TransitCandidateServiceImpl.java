package com.ssafy.ieumgil.domain.transit.service;

import com.ssafy.ieumgil.domain.block.entity.Block;
import com.ssafy.ieumgil.domain.block.repository.BlockRepository;
import com.ssafy.ieumgil.domain.place.dto.PlaceResDTO;
import com.ssafy.ieumgil.domain.place.service.PlaceQueryService;
import com.ssafy.ieumgil.domain.project.entity.Project;
import com.ssafy.ieumgil.domain.project.entity.TransportPref;
import com.ssafy.ieumgil.domain.project.repository.ProjectRepository;
import com.ssafy.ieumgil.domain.transit.dto.OdsayRouteResponse;
import com.ssafy.ieumgil.domain.transit.dto.TransitCandidateResDTO;
import com.ssafy.ieumgil.domain.transit.dto.TransitCandidateResDTO.Candidate;
import com.ssafy.ieumgil.domain.transit.dto.TransitCandidateResDTO.CandidateStatus;
import com.ssafy.ieumgil.domain.transit.dto.TransitCandidateResDTO.TransitMode;
import com.ssafy.ieumgil.domain.transit.dto.TransitLegResDTO;
import com.ssafy.ieumgil.domain.transit.dto.TransitResDTO;
import com.ssafy.ieumgil.domain.transit.exception.TransitErrorCode;
import com.ssafy.ieumgil.domain.transit.exception.TransitException;
import com.ssafy.ieumgil.domain.transit.util.Haversine;
import com.ssafy.ieumgil.domain.transit.util.IntercityLegs;
import com.ssafy.ieumgil.domain.transit.util.LandReachability;
import com.ssafy.ieumgil.domain.transit.util.ParallelInvoker;
import com.ssafy.ieumgil.global.exception.CustomException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.Semaphore;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 다른 QueryServiceImpl과 달리 {@code @Transactional(readOnly = true)}를 의도적으로 붙이지 않는다.
 *
 * <p>이 서비스는 외부 API 호출 구간을 두 단계로 감싼다. 1단은 모든 구간의 경로·길찾기 조회로
 * {@link #OVERALL_TIMEOUT}(20초)까지, 2단은 시외 구간마다의 시간표 조회로(예산은 요청 전체가 공유)
 * {@link #TIMETABLE_TIMEOUT}(20초)까지 걸릴 수 있다 — 두 단계는 순차이므로 <b>최악 40초</b>다.
 * 두 상한 모두 가상 스레드 {@code invokeAll}({@link ParallelInvoker})로 강제하며, 상한을 넘긴
 * 조회는 "조회 실패"로 내려간다.
 *
 * <p>2단(시외 door-to-door 조립)은 {@link IntercityCandidateAssembler}로 위임한다 — 이 클래스는
 * 오케스트레이션과 1단(구간별 경로 조회)·시내·도로 후보만 맡는다. 2단은 시간표 API 호출뿐 아니라
 * 수단별 접근·이탈 경로 조회({@link #accessLegsOf})까지 포함한다 — 수단마다 승차·하차 지점이
 * 달라 접근·이탈이 실제로 다르므로, 시외 경로 하나가 door-to-door로 확정되기까지 경로 조회를
 * 최대 7회 부를 수 있다. 이 추가 호출은 1단({@link #fetchRoadResults})의 {@link #distinctLegsOf}
 * 중복 제거·{@value #MAX_CONCURRENT_CALLS}개 세마포어를 거치지 않고, {@link #TIMETABLE_TIMEOUT}
 * 예산을 시간표 조회와 그대로 나눠 쓴다.
 *
 * <p>트랜잭션을 열어 두면 그동안 DB 커넥션을 붙잡아 몇 개의 동시 요청만으로 커넥션 풀이 고갈된다.
 */
@Slf4j
@Service
public class TransitCandidateServiceImpl implements TransitCandidateService {

    /** 직선 2km를 넘으면 도보를 후보에서 뺀다. 실경로로는 2.4~2.8km, 35~40분이라 걸을 거리가 아니다 */
    private static final double WALK_MAX_METERS = 2_000;
    /** 직선 300m 미만은 도보만. 5분 거리에 대중교통·택시를 물어보는 것은 쿼터 낭비다 */
    private static final double NEAR_METERS = 300;
    /** 준중형 가솔린 복합 연비 대략치(km/L). 차종을 받지 않으므로 가정값이다 */
    private static final double FUEL_EFFICIENCY_KM_PER_L = 12.0;
    private static final int MAX_CONCURRENT_CALLS = 8;
    private static final Duration OVERALL_TIMEOUT = Duration.ofSeconds(20);
    /**
     * 2단 시간표 조회의 상한. 세 수단 모두 subPath가 준 역 ID를 이름 검색 없이 그대로 시간표
     * API에 넘기므로 수단마다 시간표 조회 1회뿐이다(실측 89/89 성공). 세 수단을 직렬로 두면
     * read-timeout 15초짜리 호출이 최대 3번 쌓인다(약 45초). 동시에 부르고 상한을 건다.
     *
     * <p>{@code final}이 아니다 — Day 예산 공유(진짜 20초를 기다리지 않고도) 테스트가 값을
     * 잠깐 줄여 썼다가 되돌릴 수 있어야 한다. 프로덕션에서 이 값을 바꾸는 코드는 없다.
     */
    static Duration TIMETABLE_TIMEOUT = Duration.ofSeconds(20);
    private static final String SKIP_REASON_NO_START_TIME = "출발 블록에 시작 시각이 없어 기준 시각을 계산할 수 없습니다";
    private static final String SKIP_REASON_NO_START_DATE = "프로젝트 시작일이 없어 운행 요일을 확인할 수 없습니다";
    /** 시간표 조회 예산({@link #TIMETABLE_TIMEOUT})을 다른 Day가 이미 다 써서 이 Day는 조회를 시도조차 못 할 때 */
    private static final String SKIP_REASON_TIMETABLE_BUDGET_EXHAUSTED =
            "다른 Day의 시간표 조회가 시간을 다 써서 확인하지 못했습니다";
    /**
     * ODsay pathType — 11 기차, 12 고속버스, 13 항공, 14 해운, 20 복합.
     *
     * <p>해운(14)을 빼면 안 된다. 시외 경로는 {@code payment}도 환승 수도 주지 않는데,
     * {@link TransitRouteSelector#transferCount}가 null을 0으로 읽어 "환승 최소" 축을 이겨 버린다.
     */
    private static final Set<Integer> INTERCITY_PATH_TYPES = Set.of(11, 12, 13, 14, 20);

    private final BlockRepository blockRepository;
    private final ProjectRepository projectRepository;
    private final PlaceQueryService placeQueryService;
    private final FuelPriceProvider fuelPriceProvider;
    private final TransitRouteSelector routeSelector;
    /** 대중교통 경로 조회 협력자 — 1단(여기)과 2단(조립기)이 공유한다 */
    private final TransitRouteLookup routeLookup;
    /** 시외 door-to-door 조립(2단) 위임 대상 */
    private final IntercityCandidateAssembler intercityAssembler;

    /**
     * {@code @RequiredArgsConstructor} 대신 명시적 생성자를 쓴다 — 주입받은 협력자로
     * {@link TransitRouteLookup}·{@link IntercityCandidateAssembler}를 직접 조립하기 때문이다.
     * 두 조립기를 별도 빈으로 만들지 않는 이유는 단위 테스트가 {@code @InjectMocks}로 이 서비스에
     * 목을 꽂으므로 생성자 시그니처(주입 협력자 7개)를 그대로 유지해야 해서다.
     */
    public TransitCandidateServiceImpl(
            BlockRepository blockRepository,
            ProjectRepository projectRepository,
            PlaceQueryService placeQueryService,
            PublicTransitQueryService publicTransitQueryService,
            FuelPriceProvider fuelPriceProvider,
            TransitScheduleQueryService transitScheduleQueryService,
            TransitRouteSelector routeSelector) {
        this.blockRepository = blockRepository;
        this.projectRepository = projectRepository;
        this.placeQueryService = placeQueryService;
        this.fuelPriceProvider = fuelPriceProvider;
        this.routeSelector = routeSelector;
        this.routeLookup = new TransitRouteLookup(publicTransitQueryService);
        this.intercityAssembler =
                new IntercityCandidateAssembler(placeQueryService, transitScheduleQueryService, routeLookup);
    }

    @Override
    public TransitCandidateResDTO.Result calculate(Long projectId, List<Long> blockIds) {
        Project project = projectRepository.findAliveByIdOrThrow(projectId);
        List<Pair> pairs = pairsOf(blockIds, loadBlocks(projectId, blockIds));

        // 1단: 구간마다 시내 경로·자차·택시를 병렬로 모은다. 시외 여부는 여기서 받은 pathType으로 판정된다.
        Map<Leg, RoadResult> roadByLeg = fetchRoadResults(distinctLegsOf(pairs), project.getTransportPrefs());

        // 2단: 구간마다 독립적으로 기준 시각을 구해 시간표를 붙인다. 블록은 연속적이지 않다 —
        // resolveOverlaps가 블록 사이 공백을 그대로 보존하므로(BLK 이동 추가 칩의 근거), 구간의
        // 기준 시각은 앞 구간에서 누적한 값이 아니라 이 구간의 from 블록에 저장된 시각 그대로다
        // ({@link #baseMinutesOf}).
        //
        // 시간표 조회 예산({@link #TIMETABLE_TIMEOUT})은 요청 전체가 공유한다 — 여기서 데드라인을
        // 한 번만 잡고 구간마다 "남은" 시간만 쓰게 한다.
        Instant timetableDeadline = Instant.now().plus(TIMETABLE_TIMEOUT);
        List<TransitCandidateResDTO.Segment> segments = new ArrayList<>();
        // 자차·대중교통을 모두 고른 프로젝트는 구간마다 기본 수단을 강제하지 않는다 — 사용자가 그때그때 고른다.
        boolean multiPref = project.getTransportPrefs() != null && project.getTransportPrefs().size() > 1;

        for (Pair pair : pairs) {
            RoadResult road = roadByLeg.get(legOf(pair.from(), pair.to()));

            TransitCandidateResDTO.Segment segment;
            if (!road.isIntercity()) {
                segment = citySegment(pair, road, multiPref);
            } else {
                Integer base = baseMinutesOf(pair.from());
                if (base == null) {
                    // from 블록에 시각이 없으면 이 구간의 기준을 만들 수 없다. 앞 구간에서 값을
                    // 끌어오지 않는다 — 그게 바로 공백을 무시하던 누적 모델의 버그다.
                    segment = intercityAssembler.intercitySegmentWithoutTimetable(
                            pair, road, SKIP_REASON_NO_START_TIME, multiPref);
                } else if (project.getStartDate() == null) {
                    // 여기서는 시작일이 있는지만 본다 — 실제 여행 날짜는 수단마다 다를 수 있어
                    // 수단별로 따로 계산한다. 시작일이 없을 때 오늘로 갈음하지 않는다 — 모른다는
                    // 사실을 그대로 내보내는 편이 낫다.
                    segment = intercityAssembler.intercitySegmentWithoutTimetable(
                            pair, road, SKIP_REASON_NO_START_DATE, multiPref);
                } else {
                    Duration timetableBudget = remainingBudget(timetableDeadline);
                    if (timetableBudget.isZero()) {
                        // 다른 구간(또는 다른 Day)이 공유 예산을 이미 다 썼다.
                        segment = intercityAssembler.intercitySegmentWithoutTimetable(
                                pair, road, SKIP_REASON_TIMETABLE_BUDGET_EXHAUSTED, multiPref);
                    } else {
                        segment = intercityAssembler.intercitySegment(
                                pair, road, base, project.getStartDate(), dayNoOf(pair.from()), timetableBudget,
                                multiPref);
                    }
                }
            }
            segments.add(segment);
        }
        return TransitCandidateResDTO.Result.builder().segments(segments).build();
    }

    /**
     * {@link IntercityCandidateAssembler#accessLegsOf}로 위임한다.
     *
     * <p>package-private로 서비스 표면에 남긴 이유는 {@code TransitCandidateServiceImplTest}가
     * 이 메서드를 {@link #TIMETABLE_TIMEOUT}과 같은 이유로 직접 부르기 때문이다.
     */
    Optional<AccessLegs> accessLegsOf(Pair pair, IntercityLegs legs) {
        return intercityAssembler.accessLegsOf(pair, legs);
    }

    /**
     * 이 구간을 떠날 수 있는 시각(자정 기준 분). {@code from} 블록의 저장된 종료 시각이다.
     *
     * <p>앞 구간의 결과를 누적하지 않는다 — 블록은 연속적이지 않고 사이 공백이 실재하므로,
     * 이 구간이 볼 수 있는 진실은 {@code from} 블록 자신에 저장된 오프셋뿐이다. 보드에 놓이지
     * 않은 후보(POOL) 블록은 기준을 만들 수 없어 {@code null}이다.
     */
    private Integer baseMinutesOf(Block from) {
        Integer start = from.startMinuteOfDay();
        if (start == null) {
            return null;
        }
        return start + from.getDurationMin();
    }

    /**
     * 공유 데드라인까지 남은 시간(음수면 0으로 바닥 처리). Day마다 새 {@link #TIMETABLE_TIMEOUT}을
     * 주지 않고 이 값을 2단 호출의 실제 상한으로 쓴다 — 그래야 여러 Day를 담은 요청에서도 2단
     * 전체가 {@link #TIMETABLE_TIMEOUT} 하나를 공유한다.
     */
    private Duration remainingBudget(Instant deadline) {
        Duration remaining = Duration.between(Instant.now(), deadline);
        return remaining.isNegative() ? Duration.ZERO : remaining;
    }

    /** 블록의 Day 번호(오프셋에서 파생). 후보(POOL)면 1일차로 본다 — 여행 날짜 계산의 기본값이다. */
    private int dayNoOf(Block block) {
        return block.isInPool() ? 1 : block.dayNo();
    }

    /** 요청 순서대로 연속 쌍을 만든다. 같은 블록이 연달아 오면 이동이 없으므로 구간을 만들지 않는다. */
    private List<Pair> pairsOf(List<Long> blockIds, Map<Long, Block> blocks) {
        List<Pair> pairs = new ArrayList<>();
        for (int i = 0; i + 1 < blockIds.size(); i++) {
            if (blockIds.get(i).equals(blockIds.get(i + 1))) {
                continue;
            }
            pairs.add(new Pair(blocks.get(blockIds.get(i)), blocks.get(blockIds.get(i + 1))));
        }
        return pairs;
    }

    private Set<Leg> distinctLegsOf(List<Pair> pairs) {
        return pairs.stream()
                .map(pair -> legOf(pair.from(), pair.to()))
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    /**
     * 구간마다 가상 스레드 하나를 띄워 병렬로 외부 API를 부른다({@link ParallelInvoker}).
     *
     * <p>세마포어로 동시 호출을 {@value #MAX_CONCURRENT_CALLS}개로 묶는다 — 30블록 요청이면
     * 구간이 29개라 그대로 풀면 외부 API의 초당 한도를 넘긴다. 전체 상한도 둔다: 한 구간이
     * 늘어져도 요청 전체가 매달려 있으면 안 되고, 늦은 구간은 "조회 실패"로 내려보내면 그만이다.
     *
     * <p>시간표 조회는 여기 없다. 기준 시각이 앞 구간의 결과에 달려 있어 병렬로 만들 수 없다 —
     * 2단({@link IntercityCandidateAssembler})의 몫이다.
     */
    private Map<Leg, RoadResult> fetchRoadResults(Set<Leg> legs, List<TransportPref> prefs) {
        List<Leg> ordered = List.copyOf(legs);
        Semaphore permits = new Semaphore(MAX_CONCURRENT_CALLS);
        List<Callable<RoadResult>> tasks = ordered.stream()
                .map(leg -> (Callable<RoadResult>) () -> {
                    permits.acquire();
                    try {
                        return roadResultOf(leg, modesFor(prefs, straightDistanceOf(leg)));
                    } finally {
                        permits.release();
                    }
                })
                .toList();

        try {
            List<RoadResult> results = ParallelInvoker.invokeAllWithin(
                    tasks, OVERALL_TIMEOUT, i -> unavailableFor(ordered.get(i), prefs), "교통 후보 구간 조회");
            Map<Leg, RoadResult> byLeg = new HashMap<>();
            for (int i = 0; i < ordered.size(); i++) {
                byLeg.put(ordered.get(i), results.get(i));
            }
            return byLeg;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CustomException(TransitErrorCode.ROUTE_NOT_FOUND);
        }
    }

    /** 경로 목록이 비어 있으므로 시외로 판정되지 않는다 — 조회 실패는 시내 구간의 조회 실패로 나간다. */
    private RoadResult unavailableFor(Leg leg, List<TransportPref> prefs) {
        LegModes modes = modesFor(prefs, straightDistanceOf(leg));
        return new RoadResult(List.of(), modes.transit(),
                modes.road().stream().map(mode -> Candidate.lookupFailed(mode.mode())).toList(), false, false);
    }

    /**
     * 요청한 id를 전부 이 프로젝트에서 찾지 못하면 거절한다.
     *
     * <p>없는 블록과 남의 프로젝트 블록을 구분하지 않는다 — 구분해 알려주면 id를 넣어보는 것만으로
     * 남의 블록 존재 여부를 알아낼 수 있다. 레포 쿼리가 프로젝트 조건을 걸어 두므로 둘 다 여기서 걸린다.
     */
    private Map<Long, Block> loadBlocks(Long projectId, List<Long> blockIds) {
        Map<Long, Block> blocks = blockRepository
                .findAllByIdInAndProject_IdAndDeletedAtIsNull(blockIds, projectId).stream()
                .collect(Collectors.toMap(Block::getId, Function.identity()));
        if (!blocks.keySet().containsAll(blockIds)) {
            throw new TransitException(TransitErrorCode.INVALID_BLOCKS);
        }
        // 장소성 없는 블록(ETC 등)은 좌표가 없다. 뒤늦게 NPE로 터지느니 요청 전체를 여기서 막는다.
        boolean anyWithoutCoordinates = blocks.values().stream()
                .anyMatch(block -> block.getLat() == null || block.getLng() == null);
        if (anyWithoutCoordinates) {
            throw new TransitException(TransitErrorCode.COORDINATE_REQUIRED);
        }
        return blocks;
    }

    /**
     * 선호 이동수단이 곧 후보의 우선순위다 — 앞쪽이 기본이 되고, 조회에 실패하면 뒤로 밀린다.
     *
     * <p>CAR 프로젝트에 대중교통을 넣지 않는 이유는 "차로 다니겠다"는 선택을 매 구간 되묻지
     * 않기 위해서다. 택시는 두 선호 모두에 남긴다 — 차가 있어도 술자리처럼 택시가 필요한 구간이 있다.
     *
     * <p>거리는 직선거리다. 외부 API를 부르기 <b>전에</b> 판정해야 호출 자체를 걸러낼 수 있다.
     */
    static LegModes modesFor(List<TransportPref> prefs, double straightM) {
        // 5분 거리에 대중교통·택시를 물어봐야 답도 도보와 다르지 않다. 호출을 통째로 생략한다.
        if (straightM < NEAR_METERS) {
            return new LegModes(false, List.of(RoadMode.WALK));
        }

        List<RoadMode> road = new ArrayList<>();
        boolean car = prefs != null && prefs.contains(TransportPref.CAR);
        // 선호 미선택(빈/null) → 대중교통이 보편적 기본(기존 동작 유지)
        boolean transit = prefs == null || prefs.isEmpty() || prefs.contains(TransportPref.PUBLIC);
        if (car) {
            road.add(RoadMode.CAR);
        }
        road.add(RoadMode.TAXI);
        // 먼 구간의 도보는 목록에서 빠진다 — status=LOOKUP_FAILED가 아니라 부재다.
        // 둘을 뭉개면 프론트가 "먼 것인가 API가 죽은 것인가"를 구분하지 못한다.
        if (straightM <= WALK_MAX_METERS) {
            road.add(RoadMode.WALK);
        }
        return new LegModes(transit, List.copyOf(road));
    }

    /**
     * 1단의 한 구간. 외부 조회는 여기서 끝나고, 후보를 어떻게 나눌지는 2단이 정한다.
     *
     * <p>대중교통 경로 목록을 후보로 바꾸지 않고 그대로 들고 나가는 이유: 시내면 선정기가
     * 5개로 나누고 시외면 아예 쓰지 않는데, 그 판정에 필요한 {@code pathType}이 이 응답에 있다.
     */
    private RoadResult roadResultOf(Leg leg, LegModes modes) {
        // 택시와 자차는 같은 카카오 길찾기 응답을 나눠 쓴다 — 요금 계산만 다를 뿐 경로는 같다.
        boolean needsDriving = modes.road().contains(RoadMode.TAXI) || modes.road().contains(RoadMode.CAR);
        // 시내 후보 선정·시외 판정·도서 목적지 판정이 같은 대중교통 경로 목록을 쓴다 — 여기서 한 번만
        // 물어 세 자리에서 나눠 쓴다. 셋 다 필요 없는 leg(도보만)라면 부르지 않는다.
        TransitRouteLookup.RouteLookup lookup = modes.transit() || needsDriving
                ? routeLookup.of(leg.fromLat(), leg.fromLng(), leg.toLat(), leg.toLng())
                : TransitRouteLookup.empty();
        // 차로 갈 수 없는 목적지(시외 경로가 전부 항공)면 자차·택시를 아예 만들지 않는다. 후보를
        // 만들어 두고 status로 감추지 않는 이유: 프론트는 status와 무관하게 모든 후보를 그리므로
        // "조회 실패" 회색 줄이 남고, 그게 사용자가 신고한 증상이다. 도보는 그대로 둔다.
        boolean landUnreachable = LandReachability.isLandUnreachable(intercityPathsOf(lookup.paths()));
        // 버릴 답에 카카오 쿼터를 쓰지 않는다 — 판정 근거인 경로 목록은 이미 위에서 받았다.
        PlaceResDTO.TaxiRoute driving = needsDriving && !landUnreachable ? callDriving(leg) : null;

        List<Candidate> roadCandidates = new ArrayList<>();
        for (RoadMode mode : modes.road()) {
            if (landUnreachable && (mode == RoadMode.CAR || mode == RoadMode.TAXI)) {
                continue;
            }
            Candidate candidate = switch (mode) {
                case TAXI -> taxiCandidate(driving);
                case CAR -> carCandidate(driving);
                case WALK -> walkCandidate(leg);
            };
            roadCandidates.add(candidate);
        }
        return new RoadResult(lookup.paths(), modes.transit(), List.copyOf(roadCandidates),
                lookup.noRoute(), lookup.tooClose());
    }

    /** 시외 경로만 남긴다({@link #INTERCITY_PATH_TYPES}). 시내 구간이면 빈 목록이다 */
    private static List<OdsayRouteResponse.Path> intercityPathsOf(List<OdsayRouteResponse.Path> paths) {
        return paths.stream().filter(path -> INTERCITY_PATH_TYPES.contains(path.pathType())).toList();
    }

    /**
     * 시내 구간.
     *
     * <p>ODsay가 경로 자체를 주지 않은 구간({@link RoadResult#noRoute})도 여기로 온다 —
     * 경로가 없으니 {@code pathType}도 없어 시외로 판정될 수 없다. 그 경우 대중교통 후보는
     * {@code LOOKUP_FAILED}가 아니라 {@code NO_ROUTE}다. 실측 157경로 중 38개(도서 전량)가
     * 여기고, 조회 실패로 내면 사용자가 영원히 같은 답을 받으며 재시도한다. 자차·택시는
     * 카카오 길찾기가 따로 답하므로 이 판정과 무관하게 그대로 남는다.
     */
    private TransitCandidateResDTO.Segment citySegment(Pair pair, RoadResult road, boolean multiPref) {
        List<Candidate> candidates = new ArrayList<>();
        // 700m 이내면 대중교통 후보를 만들지 않는다 — ODsay가 낼 경로가 없다고 답한 것이지
        // 조회가 실패한 게 아니고, 그 거리는 이미 도보 후보가 답한다(2km까지 도보가 목록에 있다).
        if (road.transitRequested() && !road.tooClose()) {
            candidates.addAll(road.noRoute()
                    ? List.of(Candidate.noRoute(TransitMode.TRANSIT))
                    : transitCandidates(road.paths()));
        }
        candidates.addAll(road.roadCandidates());

        return TransitCandidateResDTO.Segment.builder()
                .fromBlockId(pair.from().getId())
                .toBlockId(pair.to().getId())
                .intercity(false)
                .timetableApplied(false)
                .defaultMode(multiPref ? null : defaultModeOf(candidates))
                .candidates(candidates)
                .build();
    }

    /**
     * 시내 대중교통 후보. 첫 경로 하나만 쓰지 않고 {@link TransitRouteSelector}로 최대 5개를 고른다.
     *
     * <p>경로 목록이 비어 있으면(조회 실패 포함) 후보 하나를 {@code status=LOOKUP_FAILED}로 남긴다 —
     * 목록에서 아예 빼면 프론트가 "먼 것인가 API가 죽은 것인가"를 구분하지 못한다.
     */
    private List<Candidate> transitCandidates(List<OdsayRouteResponse.Path> paths) {
        List<TransitRouteSelector.Selected> selected = routeSelector.selectTop5(paths);
        if (selected.isEmpty()) {
            return List.of(Candidate.lookupFailed(TransitMode.TRANSIT));
        }
        return selected.stream().map(this::transitCandidate).toList();
    }

    private Candidate transitCandidate(TransitRouteSelector.Selected selected) {
        OdsayRouteResponse.Info info = selected.path().info();
        return Candidate.builder()
                .mode(TransitMode.TRANSIT)
                .label(TransitMode.TRANSIT.label())
                .status(CandidateStatus.OK)
                .durationMin(info.totalTime())
                .fare(info.payment())
                .fareConfidence(TransitResDTO.confidenceOf(info.payment()))
                .intervalMin(info.totalIntervalTime())
                .distanceM(info.totalDistance())
                .labels(selected.labels())
                .transferCount(TransitRouteSelector.transferCount(selected.path()))
                .walkMeters(info.totalWalk())
                .legs(TransitLegResDTO.fromSubPaths(selected.path().subPath()))
                .build();
    }

    /** 실패는 후보 하나가 비는 것으로 끝난다 — 한 수단이 죽었다고 구간 전체를 못 내면 안 된다. */
    private PlaceResDTO.TaxiRoute callDriving(Leg leg) {
        try {
            return placeQueryService
                    .getTaxiRoute(leg.fromLat(), leg.fromLng(), leg.toLat(), leg.toLng())
                    .orElse(null);
        } catch (RuntimeException e) {
            log.warn("택시/자차 경로 조회 실패: leg={}", leg, e);
            return null;
        }
    }

    private Candidate taxiCandidate(PlaceResDTO.TaxiRoute driving) {
        if (driving == null) {
            return Candidate.lookupFailed(TransitMode.TAXI);
        }
        return Candidate.builder()
                .mode(TransitMode.TAXI)
                .label(TransitMode.TAXI.label())
                .status(CandidateStatus.OK)
                .durationMin(driving.durationMin())
                .fare(driving.fare())
                .fareConfidence(TransitResDTO.FareConfidence.CONFIRMED)
                .distanceM(driving.distance())
                .build();
    }

    /**
     * 자차는 택시와 같은 경로를 쓰되 요금만 다르게 계산한다 — 택시 요금은 남의 차를 타는 값이라
     * 내 차로 가는 비용이 아니다. 통행료는 카카오가 준 실측이고 연료비만 추정이므로 ESTIMATE다.
     */
    private Candidate carCandidate(PlaceResDTO.TaxiRoute driving) {
        if (driving == null) {
            return Candidate.lookupFailed(TransitMode.CAR);
        }
        return Candidate.builder()
                .mode(TransitMode.CAR)
                .label(TransitMode.CAR.label())
                .status(CandidateStatus.OK)
                .durationMin(driving.durationMin())
                .fare(driving.toll() + estimateFuelCost(driving.distance()))
                .fareConfidence(TransitResDTO.FareConfidence.ESTIMATE)
                .distanceM(driving.distance())
                .build();
    }

    /** 거리(m) ÷ 연비 × 유가. 차종을 모르니 연비는 가정값이고, 그래서 요금이 ESTIMATE로 나간다. */
    private int estimateFuelCost(int distanceM) {
        double liters = distanceM / 1_000.0 / FUEL_EFFICIENCY_KM_PER_L;
        return (int) Math.round(liters * fuelPriceProvider.pricePerLiter());
    }

    private Candidate walkCandidate(Leg leg) {
        Optional<PlaceResDTO.WalkingRoute> route;
        try {
            route = placeQueryService.getWalkingRoute(
                    leg.fromLat(), leg.fromLng(), leg.toLat(), leg.toLng());
        } catch (RuntimeException e) {
            log.warn("도보 경로 조회 실패: leg={}", leg, e);
            route = Optional.empty();
        }
        return route
                .map(r -> Candidate.builder()
                        .mode(TransitMode.WALK)
                        .label(TransitMode.WALK.label())
                        .status(CandidateStatus.OK)
                        .durationMin(r.durationMin())
                        .fare(0)
                        .fareConfidence(TransitResDTO.FareConfidence.CONFIRMED)
                        .distanceM(r.distance())
                        .build())
                .orElseGet(() -> Candidate.lookupFailed(TransitMode.WALK));
    }

    private double straightDistanceOf(Leg leg) {
        return Haversine.distanceMeters(leg.fromLat(), leg.fromLng(), leg.toLat(), leg.toLng());
    }

    /**
     * 앞에서부터 살아있는 첫 후보가 기본이다. 전부 실패했으면 null — 프론트가 그 구간만 비워 둔다.
     *
     * <p>탈 수 있는 편이 없는 시외 후보는 건너뛴다. 조회는 성공했으니 {@code status=OK}지만
     * 고를 편이 없는 수단을 기본으로 내밀 수는 없다.
     *
     * <p>{@code static}인 이유는 {@link IntercityCandidateAssembler}가 시외 구간을 조립할 때도
     * 같은 규칙으로 기본 수단을 고르기 때문이다 — 시내·시외가 같은 함수를 공유한다.
     */
    static TransitMode defaultModeOf(List<Candidate> candidates) {
        return candidates.stream()
                .filter(candidate -> candidate.status() == CandidateStatus.OK)
                .filter(candidate -> candidate.departures() == null || !candidate.departures().isEmpty())
                .map(Candidate::mode)
                .findFirst()
                .orElse(null);
    }

    private Leg legOf(Block from, Block to) {
        return new Leg(
                from.getLat().doubleValue(), from.getLng().doubleValue(),
                to.getLat().doubleValue(), to.getLng().doubleValue());
    }

    /**
     * 응답 구간 하나에 대응하는 블록 쌍. 좌표가 같아도 서로 다른 블록이면 별개의 구간이다.
     *
     * <p>package-private인 이유는 {@code TransitCandidateServiceImplTest}가 {@link #accessLegsOf}를
     * 직접 부르며 이 타입을 써야 하기 때문이다.
     */
    record Pair(Block from, Block to) {
    }

    /**
     * 접근·이탈 경로({@link #accessLegsOf}의 결과).
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
     * 외부 조회의 단위. 블록 id가 아니라 좌표로 잡는다 — 좌표가 같으면 답도 같으므로
     * 서로 다른 블록이어도 호출을 한 번으로 줄일 수 있다. 방향은 구분한다(대중교통 경로는 대칭이 아니다).
     */
    record Leg(double fromLat, double fromLng, double toLat, double toLng) {
    }

    /**
     * 1단이 실제로 조회하는 도로 수단.
     *
     * <p>{@link TransitMode}를 그대로 쓰지 않는 이유: 시외 수단(TRAIN·EXPRESS_BUS·AIR)까지 담을 수
     * 있는 타입을 1단 switch에 넣으면 컴파일러가 그 분기를 요구하고, 실제로는 올 수 없는 값이라
     * 예외를 던지는 arm이 생긴다 — 그 순간 "여기 닿지 않는다"는 보장이 컴파일 시점에서 런타임으로
     * 내려앉는다. 시외 수단을 담을 수 없는 타입을 쓰면 그 보장이 타입으로 돌아온다.
     */
    enum RoadMode {
        TAXI(TransitMode.TAXI),
        CAR(TransitMode.CAR),
        WALK(TransitMode.WALK);

        private final TransitMode mode;

        RoadMode(TransitMode mode) {
            this.mode = mode;
        }

        TransitMode mode() {
            return mode;
        }
    }

    /**
     * 한 구간에서 다룰 수단. 시내 대중교통은 경로 목록을 2단이 후보 여러 개로 나누므로
     * 여기서는 "물어볼지 말지"만 들고 있다.
     */
    record LegModes(boolean transit, List<RoadMode> road) {
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
