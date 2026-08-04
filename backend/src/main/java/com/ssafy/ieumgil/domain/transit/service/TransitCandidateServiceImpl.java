package com.ssafy.ieumgil.domain.transit.service;

import com.ssafy.ieumgil.domain.block.entity.Block;
import com.ssafy.ieumgil.domain.block.repository.BlockRepository;
import com.ssafy.ieumgil.domain.place.dto.PlaceResDTO;
import com.ssafy.ieumgil.domain.place.service.PlaceQueryService;
import com.ssafy.ieumgil.domain.project.entity.Project;
import com.ssafy.ieumgil.domain.project.entity.TransportPref;
import com.ssafy.ieumgil.domain.project.exception.ProjectErrorCode;
import com.ssafy.ieumgil.domain.project.repository.ProjectRepository;
import com.ssafy.ieumgil.domain.transit.client.DomesticAirport;
import com.ssafy.ieumgil.domain.transit.dto.OdsayRouteResponse;
import com.ssafy.ieumgil.domain.transit.dto.TransitCandidateResDTO;
import com.ssafy.ieumgil.domain.transit.dto.TransitCandidateResDTO.Candidate;
import com.ssafy.ieumgil.domain.transit.dto.TransitCandidateResDTO.TransitMode;
import com.ssafy.ieumgil.domain.transit.dto.TransitLegResDTO;
import com.ssafy.ieumgil.domain.transit.dto.TransitResDTO;
import com.ssafy.ieumgil.domain.transit.dto.TransitScheduleResDTO;
import com.ssafy.ieumgil.domain.transit.exception.TransitErrorCode;
import com.ssafy.ieumgil.domain.transit.exception.TransitException;
import com.ssafy.ieumgil.domain.transit.util.Haversine;
import com.ssafy.ieumgil.domain.transit.util.SegmentClock;
import com.ssafy.ieumgil.global.exception.CustomException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 다른 QueryServiceImpl과 달리 {@code @Transactional(readOnly = true)}를 의도적으로 붙이지 않는다.
 *
 * <p>이 서비스는 외부 API 호출 구간을 두 단계로 감싼다. 1단은 모든 구간의 경로·길찾기 조회로
 * {@link #OVERALL_TIMEOUT}(20초)까지, 2단은 첫 시외 구간의 시간표 조회로
 * {@link #TIMETABLE_TIMEOUT}(20초)까지 걸릴 수 있다 — 두 단계는 순차이므로 <b>최악 40초</b>다.
 * 두 상한 모두 가상 스레드 {@code invokeAll}로 강제하며, 상한을 넘긴 조회는 "조회 실패"로 내려간다.
 *
 * <p>트랜잭션을 열어 두면 그동안 DB 커넥션을 붙잡아 몇 개의 동시 요청만으로 커넥션 풀이 고갈된다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
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
     * 2단 시간표 조회의 상한. 기차·고속버스는 터미널 검색 2회 + 시간표 1회, 항공은 공항을
     * {@link DomesticAirport}에서 메모리로 찾으므로 시간표 1회다 — 세 수단을 직렬로 두면
     * read-timeout 15초짜리 호출이 최대 7번 쌓인다(약 105초). 동시에 부르고 상한을 건다.
     */
    private static final Duration TIMETABLE_TIMEOUT = Duration.ofSeconds(20);
    private static final LocalTime DEFAULT_DAY_START = LocalTime.of(9, 0);
    private static final String SKIP_REASON_PRIOR_INTERCITY = "앞선 시외 구간의 편이 확정되지 않았습니다";
    private static final String SKIP_REASON_NO_START_DATE = "프로젝트 시작일이 없어 운행 요일을 확인할 수 없습니다";
    /**
     * ODsay pathType — 11 기차, 12 고속버스, 13 항공, 14 해운, 20 복합.
     *
     * <p>해운(14)을 빼면 안 된다. 시외 경로는 {@code payment}도 환승 수도 주지 않는데,
     * {@link TransitRouteSelector#transferCount}가 null을 0으로 읽어 "환승 최소" 축을 이겨 버린다.
     */
    private static final Set<Integer> INTERCITY_PATH_TYPES = Set.of(11, 12, 13, 14, 20);
    /** 시외 구간이 나누는 세 수단. 이 순서가 곧 후보 순서다 */
    private static final List<TransitMode> INTERCITY_MODES =
            List.of(TransitMode.TRAIN, TransitMode.EXPRESS_BUS, TransitMode.AIR);
    private static final DateTimeFormatter HHMM = DateTimeFormatter.ofPattern("HH:mm");

    private final BlockRepository blockRepository;
    private final ProjectRepository projectRepository;
    private final PlaceQueryService placeQueryService;
    private final PublicTransitQueryService publicTransitQueryService;
    private final FuelPriceProvider fuelPriceProvider;
    private final TransitScheduleQueryService transitScheduleQueryService;
    private final TransitRouteSelector routeSelector;

    @Override
    public TransitCandidateResDTO.Result calculate(Long projectId, List<Long> blockIds, LocalTime dayStart) {
        Project project = projectRepository.findByIdAndDeletedAtIsNull(projectId)
                .orElseThrow(() -> new CustomException(ProjectErrorCode.PROJECT_NOT_FOUND));
        List<Pair> pairs = pairsOf(blockIds, loadBlocks(projectId, blockIds));

        // 1단: 구간마다 시내 경로·자차·택시를 병렬로 모은다. 시외 여부는 여기서 받은 pathType으로 판정된다.
        Map<Leg, RoadResult> roadByLeg = fetchRoadResults(distinctLegsOf(pairs), project.getTransportPref());

        // 2단: 순서대로 훑으며 기준 시각을 누적하고, Day마다 첫 시외 구간에만 시간표를 붙인다.
        // blockIds는 여러 Day를 한 체인으로 이어 보낼 수 있다(요청 크기 상한 30의 근거 자체가
        // "3일 일정에 Day당 10블록"이다) — 그래서 기준 시각과 시외 확정 플래그는 요청 전체가 아니라
        // Day 하나에서만 유지해야 한다. 그렇지 않으면 Day2의 시외 구간이 Day1의 확정 때문에
        // "앞선 시외 구간의 편이 확정되지 않았습니다"로 잘못 건너뛰어진다.
        Integer currentDayNo = null;
        SegmentClock clock = null;
        boolean intercityUsed = false;
        List<TransitCandidateResDTO.Segment> segments = new ArrayList<>();

        for (Pair pair : pairs) {
            int dayNo = dayNoOf(pair.from());
            if (!Objects.equals(currentDayNo, dayNo)) {
                // 새 Day로 넘어가면 기준 시각과 시외 확정 플래그를 다시 시작한다 — 이전 Day가
                // 얼마나 늦게 끝났든 이 Day와는 무관하다. dayStart는 요청 하나에 하나뿐이라
                // 모든 Day에 같은 값(또는 기본값)을 그대로 적용한다 — Day별로 다른 시작 시각을
                // 받으려면 요청 계약이 바뀌어야 한다(별도 논의 필요).
                clock = new SegmentClock(dayStart == null ? DEFAULT_DAY_START : dayStart);
                intercityUsed = false;
                currentDayNo = dayNo;
            }
            RoadResult road = roadByLeg.get(legOf(pair.from(), pair.to()));

            TransitCandidateResDTO.Segment segment;
            if (!road.isIntercity()) {
                segment = citySegment(pair, road);
            } else if (intercityUsed) {
                segment = intercitySegmentWithoutTimetable(pair, road, SKIP_REASON_PRIOR_INTERCITY);
            } else {
                LocalDate date = dateOf(project, pair);
                if (date == null) {
                    // 시작일을 모르면 다음 시외 구간도 마찬가지다 — intercityUsed를 세우지 않아
                    // 뒤 구간에도 "앞 구간 때문"이 아닌 진짜 이유가 붙는다.
                    segment = intercitySegmentWithoutTimetable(pair, road, SKIP_REASON_NO_START_DATE);
                } else {
                    segment = intercitySegment(pair, road, clock.reference(), date);
                    intercityUsed = true;
                }
            }
            segments.add(segment);
            clock.advance(defaultDurationOf(segment), stayMinutesOf(pair.to()));
        }
        return TransitCandidateResDTO.Result.builder().segments(segments).build();
    }

    /**
     * 여행 날짜. {@code project.startDate + (dayNo - 1)}이며, 시작일이 없으면 {@code null}이다.
     *
     * <p>요청으로 받지 않는다 — 클라이언트가 보내면 서버 값과 어긋날 여지만 생기고,
     * 블록의 {@code dayNo}와 프로젝트 시작일이 이미 서버에 있다.
     *
     * <p>시작일이 없을 때 오늘로 갈음하지 않는다. 오늘의 요일로 운행 편을 거르고 요일별 요금을
     * 고르면, 실제 여행일과 무관한 시간표를 {@code timetableApplied=true}·{@code CONFIRMED}로
     * 내보내게 된다. 모른다는 사실을 그대로 내보내는 편이 낫다.
     */
    private LocalDate dateOf(Project project, Pair pair) {
        if (project.getStartDate() == null) {
            return null;
        }
        return project.getStartDate().plusDays(dayNoOf(pair.from()) - 1L);
    }

    /** 블록의 dayNo. 미설정(null)이면 1일차로 본다 — Day 경계 판정과 여행 날짜 계산이 같은 규칙을 쓴다. */
    private int dayNoOf(Block block) {
        return block.getDayNo() == null ? 1 : block.getDayNo();
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
     * 구간마다 가상 스레드 하나를 띄워 병렬로 외부 API를 부른다.
     *
     * <p>세마포어로 동시 호출을 {@value #MAX_CONCURRENT_CALLS}개로 묶는다 — 30블록 요청이면
     * 구간이 29개라 그대로 풀면 외부 API의 초당 한도를 넘긴다. 전체 상한도 둔다: 한 구간이
     * 늘어져도 요청 전체가 매달려 있으면 안 되고, 늦은 구간은 "조회 실패"로 내려보내면 그만이다.
     *
     * <p>시간표 조회는 여기 없다. 기준 시각이 앞 구간의 결과에 달려 있어 병렬로 만들 수 없고,
     * 어차피 한 요청에 한 구간만 시간표를 쓴다.
     */
    private Map<Leg, RoadResult> fetchRoadResults(Set<Leg> legs, TransportPref pref) {
        List<Leg> ordered = List.copyOf(legs);
        Semaphore permits = new Semaphore(MAX_CONCURRENT_CALLS);
        List<Callable<RoadResult>> tasks = ordered.stream()
                .map(leg -> (Callable<RoadResult>) () -> {
                    permits.acquire();
                    try {
                        return roadResultOf(leg, modesFor(pref, straightDistanceOf(leg)));
                    } finally {
                        permits.release();
                    }
                })
                .toList();

        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        try {
            List<Future<RoadResult>> futures =
                    executor.invokeAll(tasks, OVERALL_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            Map<Leg, RoadResult> byLeg = new HashMap<>();
            for (int i = 0; i < ordered.size(); i++) {
                byLeg.put(ordered.get(i), resultOf(futures.get(i), ordered.get(i), pref));
            }
            return byLeg;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CustomException(TransitErrorCode.ROUTE_NOT_FOUND);
        } finally {
            // invokeAll이 이미 취소했지만, 인터럽트에 늦게 반응하는 호출을 기다리지 않기 위해 즉시 내린다.
            executor.shutdownNow();
        }
    }

    /** 타임아웃·예외로 끝난 구간은 모든 수단이 조회 실패인 구간으로 내려간다 — 요청 전체를 깨지 않는다. */
    private RoadResult resultOf(Future<RoadResult> future, Leg leg, TransportPref pref) {
        try {
            return future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("교통 후보 구간 조회 인터럽트: leg={}", leg);
            return unavailableFor(leg, pref);
        } catch (CancellationException e) {
            log.warn("교통 후보 구간 조회 타임아웃 취소(20초): leg={}", leg);
            return unavailableFor(leg, pref);
        } catch (ExecutionException e) {
            log.warn("교통 후보 구간 조회 실패: leg={}", leg, e.getCause());
            return unavailableFor(leg, pref);
        }
    }

    /** 경로 목록이 비어 있으므로 시외로 판정되지 않는다 — 조회 실패는 시내 구간의 조회 실패로 나간다. */
    private RoadResult unavailableFor(Leg leg, TransportPref pref) {
        LegModes modes = modesFor(pref, straightDistanceOf(leg));
        return new RoadResult(List.of(), modes.transit(),
                modes.road().stream().map(mode -> Candidate.unavailable(mode.mode())).toList());
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
    private LegModes modesFor(TransportPref pref, double straightM) {
        // 5분 거리에 대중교통·택시를 물어봐야 답도 도보와 다르지 않다. 호출을 통째로 생략한다.
        if (straightM < NEAR_METERS) {
            return new LegModes(false, List.of(RoadMode.WALK));
        }

        List<RoadMode> road = new ArrayList<>();
        // 프로젝트 생성 시 선호를 고르지 않을 수 있다(nullable). 대중교통이 더 보편적인 기본값이다.
        boolean transit = pref != TransportPref.CAR;
        if (!transit) {
            road.add(RoadMode.CAR);
        }
        road.add(RoadMode.TAXI);
        // 먼 구간의 도보는 목록에서 빠진다 — available=false가 아니라 부재다.
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
        List<OdsayRouteResponse.Path> paths =
                modes.transit() || needsDriving ? combinedRoutesFor(leg) : List.of();
        // 도서 목적지 판정: paths가 비어 있으면(조회 실패·타임아웃 포함) "모른다"는 뜻이라 지금까지처럼
        // 자차·택시를 정상적으로 만든다 — paths가 있는데 그중 육로로만 이어지는 경로가 하나도 없을 때만
        // "이 leg는 육로로 갈 수 없다"고 판정한다. 예전에는 여러 경로 중 항공·해운이 섞인 것 하나만
        // 있어도 육로가 없다고 오판했다(서울-부산처럼 기차·버스가 멀쩡히 있는데도 국내선 하나 때문에
        // 걸림) — 이제는 육로 대안이 하나도 없을 때만 자차·택시 후보 자체를 만들지 않는다.
        boolean roadUnreachable = needsDriving && !paths.isEmpty() && !hasRoadPath(paths);
        PlaceResDTO.TaxiRoute driving = needsDriving && !roadUnreachable ? callDriving(leg) : null;

        List<Candidate> roadCandidates = new ArrayList<>();
        for (RoadMode mode : modes.road()) {
            if (roadUnreachable && (mode == RoadMode.CAR || mode == RoadMode.TAXI)) {
                continue;
            }
            Candidate candidate = switch (mode) {
                case TAXI -> taxiCandidate(driving);
                case CAR -> carCandidate(driving);
                case WALK -> walkCandidate(leg);
            };
            roadCandidates.add(candidate);
        }
        return new RoadResult(paths, modes.transit(), List.copyOf(roadCandidates));
    }

    /**
     * 대중교통 경로 목록. 실패는 빈 목록이다 — 한 수단이 죽었다고 구간 전체를 못 내면 안 된다.
     */
    private List<OdsayRouteResponse.Path> combinedRoutesFor(Leg leg) {
        try {
            return publicTransitQueryService
                    .getCombinedRoutes(leg.fromLat(), leg.fromLng(), leg.toLat(), leg.toLng());
        } catch (RuntimeException e) {
            log.warn("대중교통 경로 목록 조회 실패: leg={}", leg, e);
            return List.of();
        }
    }

    /**
     * combinedRoutes 중 육로(항공·해운 leg가 없는 경로)로만 이어지는 대안이 하나라도 있는지.
     * 항공·해운 leg가 섞인 경로는 육로가 아니다 — bus+ferry처럼 일부만 육로여도 그 경로 전체는
     * 육로 대안으로 치지 않는다. 모든 경로를 본다 — 첫 번째 경로만 보면 육로 대안이 뒤에 있는
     * 경우를 놓칠 수 있다.
     */
    private boolean hasRoadPath(List<OdsayRouteResponse.Path> combinedRoutes) {
        return combinedRoutes.stream()
                .anyMatch(path -> !TransitLegResDTO.hasNonRoadLeg(TransitLegResDTO.fromSubPaths(path.subPath())));
    }

    /**
     * 시외 구간. 세 수단을 각각 만든다 — 서울→부산에서 기차 59,800원 2시간 37분과
     * 고속버스 39,700원 4시간은 사용자가 실제로 저울질하는 대안이라 하나로 뭉치면 그 선택이 사라진다.
     */
    private TransitCandidateResDTO.Segment intercitySegment(
            Pair pair, RoadResult road, LocalTime reference, LocalDate date) {
        String from = road.firstStartStation();
        String to = road.lastEndStation();

        List<Candidate> candidates = new ArrayList<>();
        if (from == null || to == null) {
            // ODsay가 역 이름을 주지 않았다. 시간표 API에 넘길 대상이 없으므로 세 수단 모두 조회 실패다.
            log.warn("시외 경로에 역 이름이 없다: from={}, to={}", from, to);
            INTERCITY_MODES.forEach(mode -> candidates.add(Candidate.unavailable(mode)));
        } else {
            candidates.addAll(intercityCandidates(from, to, reference, date));
        }
        candidates.addAll(road.roadCandidates());   // 자차·택시. 1단에서 이미 만들어졌다

        return TransitCandidateResDTO.Segment.builder()
                .fromBlockId(pair.from().getId())
                .toBlockId(pair.to().getId())
                .intercity(true)
                .timetableApplied(true)
                .referenceAt(reference.format(HHMM))
                .defaultMode(defaultModeOf(candidates))
                .candidates(candidates)
                .build();
    }

    /**
     * 시간표를 적용하지 않는 시외 구간.
     *
     * <p>앞 구간에 138분 기차가 들어가면 뒤 블록의 시각이 밀려 기준이 이미 지나간 시각이 된다.
     * 조용히 틀린 시각을 주는 대신 이유를 남겨 프론트가 재계산을 안내하게 한다.
     *
     * <p>그래도 <b>수단 슬롯은 남긴다</b> — 서울→부산→제주에서 뒤 구간만 항공이 사라지면
     * 제주에 배로 가라는 말이 된다. 편 목록만 비고({@code departures=[]}) 수단은 그대로다.
     * 조회를 안 한 것이지 실패한 것이 아니므로 {@code available=true}다.
     */
    private TransitCandidateResDTO.Segment intercitySegmentWithoutTimetable(
            Pair pair, RoadResult road, String reason) {
        List<Candidate> candidates = new ArrayList<>();
        for (TransitMode mode : INTERCITY_MODES) {
            candidates.add(departureCandidate(
                    mode, List.of(), road.firstStartStation(), road.lastEndStation()));
        }
        candidates.addAll(road.roadCandidates());

        return TransitCandidateResDTO.Segment.builder()
                .fromBlockId(pair.from().getId())
                .toBlockId(pair.to().getId())
                .intercity(true)
                .timetableApplied(false)
                .timetableSkipReason(reason)
                .referenceAt(null)
                .defaultMode(defaultModeOf(candidates))
                .candidates(candidates)
                .build();
    }

    private TransitCandidateResDTO.Segment citySegment(Pair pair, RoadResult road) {
        List<Candidate> candidates = new ArrayList<>();
        if (road.transitRequested()) {
            candidates.addAll(transitCandidates(road.paths()));
        }
        candidates.addAll(road.roadCandidates());

        return TransitCandidateResDTO.Segment.builder()
                .fromBlockId(pair.from().getId())
                .toBlockId(pair.to().getId())
                .intercity(false)
                .timetableApplied(false)
                .defaultMode(defaultModeOf(candidates))
                .candidates(candidates)
                .build();
    }

    /**
     * 시내 대중교통 후보. 첫 경로 하나만 쓰지 않고 {@link TransitRouteSelector}로 최대 5개를 고른다.
     *
     * <p>경로 목록이 비어 있으면(조회 실패 포함) 후보 하나를 {@code available=false}로 남긴다 —
     * 목록에서 아예 빼면 프론트가 "먼 것인가 API가 죽은 것인가"를 구분하지 못한다.
     */
    private List<Candidate> transitCandidates(List<OdsayRouteResponse.Path> paths) {
        List<TransitRouteSelector.Selected> selected = routeSelector.selectTop5(paths);
        if (selected.isEmpty()) {
            return List.of(Candidate.unavailable(TransitMode.TRANSIT));
        }
        return selected.stream().map(this::transitCandidate).toList();
    }

    private Candidate transitCandidate(TransitRouteSelector.Selected selected) {
        OdsayRouteResponse.Info info = selected.path().info();
        return Candidate.builder()
                .mode(TransitMode.TRANSIT)
                .label(TransitMode.TRANSIT.label())
                .available(true)
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

    /**
     * 세 수단의 시간표를 가상 스레드로 동시에 부른다.
     *
     * <p>기차·고속버스는 터미널 검색 2회 + 시간표 1회, 항공은 시간표 1회다(공항은
     * {@link DomesticAirport}에서 메모리로 찾는다). 직렬로 두면 한 요청이 read-timeout 15초 × 7회를
     * 서블릿 스레드에서 그대로 뒤집어쓴다. 1단({@link #fetchRoadResults})과 같은 방식으로
     * 동시에 부르고 {@link #TIMETABLE_TIMEOUT} 상한을 건다.
     *
     * <p>세마포어를 걸지 않는 이유: 2단은 1단이 끝난 뒤에 돌고 동시 호출이 세 개뿐이라
     * {@value #MAX_CONCURRENT_CALLS}개 상한 안이다.
     *
     * <p>순서는 {@link #INTERCITY_MODES} 그대로다 — 후보 순서가 곧 화면 순서다.
     */
    private List<Candidate> intercityCandidates(
            String from, String to, LocalTime reference, LocalDate date) {
        List<Callable<Candidate>> tasks = List.of(
                () -> trainCandidate(from, to, reference, date),
                () -> expressBusCandidate(from, to, reference, date),
                () -> airCandidate(from, to, reference, date));

        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        try {
            List<Future<Candidate>> futures =
                    executor.invokeAll(tasks, TIMETABLE_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            List<Candidate> candidates = new ArrayList<>();
            for (int i = 0; i < INTERCITY_MODES.size(); i++) {
                candidates.add(candidateOf(futures.get(i), INTERCITY_MODES.get(i)));
            }
            return candidates;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("시외 시간표 조회 인터럽트 {} -> {}", from, to);
            return INTERCITY_MODES.stream().map(Candidate::unavailable).toList();
        } finally {
            executor.shutdownNow();
        }
    }

    /** 타임아웃·예외로 끝난 수단만 조회 실패로 내려간다 — 나머지 두 수단까지 잃지 않는다. */
    private Candidate candidateOf(Future<Candidate> future, TransitMode mode) {
        try {
            return future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("시외 시간표 조회 인터럽트: mode={}", mode);
            return Candidate.unavailable(mode);
        } catch (CancellationException e) {
            log.warn("시외 시간표 조회 타임아웃 취소({}초): mode={}", TIMETABLE_TIMEOUT.toSeconds(), mode);
            return Candidate.unavailable(mode);
        } catch (ExecutionException e) {
            log.warn("시외 시간표 조회 실패: mode={}", mode, e.getCause());
            return Candidate.unavailable(mode);
        }
    }

    /**
     * 역 이름은 ODsay 경로 응답이 준 값을 그대로 시간표 API에 넘긴다.
     *
     * <p>좌표로 가장 가까운 역을 찾는 지오코딩을 만들지 않는 이유: 경로 API가 이미
     * {@code firstStartStation: "오송"}처럼 역 이름을 주고, 시간표 API가 이름 검색을 지원한다.
     * 우리가 다시 고르면 ODsay가 계산한 경로와 다른 역을 집을 수 있다.
     */
    private Candidate trainCandidate(String from, String to, LocalTime reference, LocalDate date) {
        try {
            Optional<TransitScheduleResDTO.TerminalSearchResult> start =
                    transitScheduleQueryService.searchTrainStation(from);
            Optional<TransitScheduleResDTO.TerminalSearchResult> end =
                    transitScheduleQueryService.searchTrainStation(to);
            if (start.isEmpty() || end.isEmpty()) {
                return Candidate.unavailable(TransitMode.TRAIN);
            }

            List<TransitCandidateResDTO.Departure> all = transitScheduleQueryService
                    .getTrainSchedule(start.get().stationId(), end.get().stationId(), date).stream()
                    .map(this::toTrainDeparture)
                    .toList();
            return departureCandidate(TransitMode.TRAIN,
                    DepartureSelector.selectThree(afterReference(all, reference), true), from, to);
        } catch (RuntimeException e) {
            log.warn("기차 시간표 조회 실패 {} -> {}", from, to, e);
            return Candidate.unavailable(TransitMode.TRAIN);
        }
    }

    private Candidate expressBusCandidate(String from, String to, LocalTime reference, LocalDate date) {
        try {
            Optional<TransitScheduleResDTO.TerminalSearchResult> start =
                    transitScheduleQueryService.searchExpressBusTerminal(from);
            Optional<TransitScheduleResDTO.TerminalSearchResult> end =
                    transitScheduleQueryService.searchExpressBusTerminal(to);
            if (start.isEmpty() || end.isEmpty()) {
                return Candidate.unavailable(TransitMode.EXPRESS_BUS);
            }

            List<TransitCandidateResDTO.Departure> all = transitScheduleQueryService
                    .getIntercityBusSchedule(start.get().stationId(), end.get().stationId(), date).stream()
                    .map(this::toBusDeparture)
                    .toList();
            return departureCandidate(TransitMode.EXPRESS_BUS,
                    DepartureSelector.selectThree(afterReference(all, reference), true), from, to);
        } catch (RuntimeException e) {
            log.warn("고속버스 시간표 조회 실패 {} -> {}", from, to, e);
            return Candidate.unavailable(TransitMode.EXPRESS_BUS);
        }
    }

    /** 공항은 ODsay 검색 API가 아니라 {@link DomesticAirport} 목록으로 찾는다 — 국내선 공항은 14개뿐이다. */
    private Candidate airCandidate(String from, String to, LocalTime reference, LocalDate date) {
        try {
            Optional<DomesticAirport> start = DomesticAirport.findByName(from);
            Optional<DomesticAirport> end = DomesticAirport.findByName(to);
            if (start.isEmpty() || end.isEmpty()) {
                return Candidate.unavailable(TransitMode.AIR);
            }

            List<TransitCandidateResDTO.Departure> all = transitScheduleQueryService
                    .getFlightSchedule(start.get().stationId(), end.get().stationId(), date).stream()
                    .map(this::toFlightDeparture)
                    .toList();
            // 요금이 없어 최저가 축을 쓸 수 없다. 시각순 3편이다.
            return departureCandidate(TransitMode.AIR,
                    DepartureSelector.selectThree(afterReference(all, reference), false), from, to);
        } catch (RuntimeException e) {
            log.warn("항공 시간표 조회 실패 {} -> {}", from, to, e);
            return Candidate.unavailable(TransitMode.AIR);
        }
    }

    private TransitCandidateResDTO.Departure toTrainDeparture(TransitScheduleResDTO.TrainSchedule t) {
        return TransitCandidateResDTO.Departure.builder()
                .name(t.trainClass() + " " + t.trainNo())
                .grade(t.trainClass())
                .departureAt(t.departureTime())
                .arrivalAt(t.arrivalTime())
                .durationMin(minutesBetween(t.departureTime(), t.arrivalTime()))
                .fare(t.generalFare())
                .fareConfidence(TransitResDTO.confidenceOf(t.generalFare()))
                .fareOptions(new TransitCandidateResDTO.FareOptions(
                        t.generalFare(), t.specialFare(), t.standingFare()))
                .labels(List.of())
                .build();
    }

    /**
     * 고속버스 시간표에는 도착 시각이 없다. 출발 + 소요로 만든다 — 소요시간은 시간표가 주는
     * 공식값이라 추정이 아니다.
     *
     * <p>소요시간이나 요금이 없으면 지어내지 않는다. ODsay가 그 필드를 빼고 주는 편이 있고,
     * 0으로 채우면 "즉시 도착"·"0원 확정"이 되어 나간다.
     */
    private TransitCandidateResDTO.Departure toBusDeparture(TransitScheduleResDTO.BusSchedule b) {
        Integer durationMin = b.wasteTimeMin();
        return TransitCandidateResDTO.Departure.builder()
                .name("고속버스")
                .grade(busGradeOf(b.busClass()))
                .departureAt(b.departureTime())
                .arrivalAt(durationMin == null
                        ? null
                        : LocalTime.parse(b.departureTime()).plusMinutes(durationMin).format(HHMM))
                .durationMin(durationMin)
                .fare(b.fare())
                .fareConfidence(TransitResDTO.confidenceOf(b.fare()))
                .labels(List.of())
                .build();
    }

    /**
     * ODsay {@code busClass} → 등급 이름(1 일반, 2 우등, 3 프리미엄).
     *
     * <p>모르는 코드는 null이다. {@code grade}는 "KTX"·"무궁화"·항공사명이 앉는 자리라
     * 숫자를 그대로 넣으면 화면에 "2"가 뜬다 — 이름을 지어내느니 비워 둔다.
     */
    private String busGradeOf(int busClass) {
        return switch (busClass) {
            case 1 -> "일반";
            case 2 -> "우등";
            case 3 -> "프리미엄";
            default -> null;
        };
    }

    /** 항공은 요금 필드가 ODsay에 없다. 추정하지 않는다 — 성수기·특가·항공사에 따라 몇 배로 틀린다 */
    private TransitCandidateResDTO.Departure toFlightDeparture(TransitScheduleResDTO.FlightSchedule f) {
        return TransitCandidateResDTO.Departure.builder()
                .name(f.airline() + " " + f.flightNo())
                .grade(f.airline())
                .departureAt(f.departureTime())
                .arrivalAt(f.arrivalTime())
                .durationMin(minutesBetween(f.departureTime(), f.arrivalTime()))
                .fare(null)
                .fareConfidence(TransitResDTO.FareConfidence.UNKNOWN)
                .labels(List.of())
                .build();
    }

    /**
     * 기준 시각 이후 편만 남기고 시각 오름차순으로 정렬한다.
     * {@link DepartureSelector}가 정렬을 전제하므로 여기서 보장한다.
     *
     * <p>운행 요일 필터는 이미 시간표 조회에서 끝났다 — 여기서 다시 걸지 않는다.
     */
    private List<TransitCandidateResDTO.Departure> afterReference(
            List<TransitCandidateResDTO.Departure> all, LocalTime reference) {
        return all.stream()
                .filter(d -> !LocalTime.parse(d.departureAt()).isBefore(reference))
                .sorted(Comparator.comparing(d -> LocalTime.parse(d.departureAt())))
                .toList();
    }

    /**
     * 후보의 대표값은 첫 출발편에서 가져온다.
     *
     * <p>편이 없어도 {@code available=true}다 — 조회는 성공했고 "그 시각 이후에는 없다"가
     * 유효한 답이다. {@code available=false}는 조회 실패에만 쓴다(기존 규칙).
     *
     * <p>편이 없으면 leg도 없다. 탈 편이 정해지지 않았는데 구간을 그리면 소요시간을 0분으로
     * 채우게 되고("즉시 도착"), 역 이름조차 없는 경우 {@code from}·{@code to}가 null인 구간이 남는다.
     */
    private Candidate departureCandidate(
            TransitMode mode, List<TransitCandidateResDTO.Departure> selected, String from, String to) {
        if (selected.isEmpty()) {
            return Candidate.builder()
                    .mode(mode)
                    .label(mode.label())
                    .available(true)
                    .fareConfidence(TransitResDTO.FareConfidence.UNKNOWN)
                    .transferCount(0)
                    .legs(List.of())
                    .departures(List.of())
                    .build();
        }

        TransitCandidateResDTO.Departure first = selected.get(0);
        return Candidate.builder()
                .mode(mode)
                .label(mode.label())
                .available(true)
                .durationMin(first.durationMin())
                .fare(first.fare())
                .fareConfidence(first.fareConfidence())
                .transferCount(0)
                .legs(List.of(TransitLegResDTO.Leg.builder()
                        .type(legTypeOf(mode))
                        .from(from)
                        .to(to)
                        .durationMin(first.durationMin() == null ? 0 : first.durationMin())
                        .build()))
                .departures(selected)
                .build();
    }

    private TransitLegResDTO.LegType legTypeOf(TransitMode mode) {
        return switch (mode) {
            case TRAIN -> TransitLegResDTO.LegType.TRAIN;
            case EXPRESS_BUS -> TransitLegResDTO.LegType.EXPRESS_BUS;
            case AIR -> TransitLegResDTO.LegType.AIR;
            default -> TransitLegResDTO.LegType.OTHER;
        };
    }

    private int minutesBetween(String departureAt, String arrivalAt) {
        LocalTime departure = LocalTime.parse(departureAt);
        LocalTime arrival = LocalTime.parse(arrivalAt);
        int minutes = (int) Duration.between(departure, arrival).toMinutes();
        // 자정을 넘는 편(23:30 출발 05:10 도착)은 음수가 나온다
        return minutes < 0 ? minutes + (int) Duration.ofDays(1).toMinutes() : minutes;
    }

    /**
     * 앞에서부터 살아있는 첫 후보가 기본이다. 전부 실패했으면 null — 프론트가 그 구간만 비워 둔다.
     *
     * <p>탈 수 있는 편이 없는 시외 후보는 건너뛴다. 조회는 성공했으니 {@code available=true}지만
     * ({@link #departureCandidate}), 고를 편이 없는 수단을 기본으로 내밀 수는 없다. 소요시간도
     * 없어 {@link #defaultDurationOf}가 0을 주므로 기준 시각이 그 구간만큼 밀리지 않는다.
     */
    private TransitMode defaultModeOf(List<Candidate> candidates) {
        return candidates.stream()
                .filter(Candidate::available)
                .filter(candidate -> candidate.departures() == null || !candidate.departures().isEmpty())
                .map(Candidate::mode)
                .findFirst()
                .orElse(null);
    }

    /** 기준 시각 누적에 쓸 이 구간의 이동시간. 기본 수단의 값이며 알 수 없으면 0이다. */
    private int defaultDurationOf(TransitCandidateResDTO.Segment segment) {
        if (segment.defaultMode() == null) {
            return 0;
        }
        return segment.candidates().stream()
                .filter(candidate -> candidate.mode() == segment.defaultMode())
                .map(Candidate::durationMin)
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(0);
    }

    /** 뒤 블록에 머무는 시간. 다음 구간의 출발 기준 시각은 그만큼 뒤로 밀린다. */
    private int stayMinutesOf(Block block) {
        return block.getDurationMin() == null ? 0 : block.getDurationMin();
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
            return Candidate.unavailable(TransitMode.TAXI);
        }
        return Candidate.builder()
                .mode(TransitMode.TAXI)
                .label(TransitMode.TAXI.label())
                .available(true)
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
            return Candidate.unavailable(TransitMode.CAR);
        }
        return Candidate.builder()
                .mode(TransitMode.CAR)
                .label(TransitMode.CAR.label())
                .available(true)
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
                        .available(true)
                        .durationMin(r.durationMin())
                        .fare(0)
                        .fareConfidence(TransitResDTO.FareConfidence.CONFIRMED)
                        .distanceM(r.distance())
                        .build())
                .orElseGet(() -> Candidate.unavailable(TransitMode.WALK));
    }

    private double straightDistanceOf(Leg leg) {
        return Haversine.distanceMeters(leg.fromLat(), leg.fromLng(), leg.toLat(), leg.toLng());
    }

    /** 응답 구간 하나에 대응하는 블록 쌍. 좌표가 같아도 서로 다른 블록이면 별개의 구간이다. */
    private record Pair(Block from, Block to) {
    }

    private Leg legOf(Block from, Block to) {
        return new Leg(
                from.getLat().doubleValue(), from.getLng().doubleValue(),
                to.getLat().doubleValue(), to.getLng().doubleValue());
    }

    /**
     * 외부 조회의 단위. 블록 id가 아니라 좌표로 잡는다 — 좌표가 같으면 답도 같으므로
     * 서로 다른 블록이어도 호출을 한 번으로 줄일 수 있다. 방향은 구분한다(대중교통 경로는 대칭이 아니다).
     */
    private record Leg(double fromLat, double fromLng, double toLat, double toLng) {
    }

    /**
     * 1단이 실제로 조회하는 도로 수단.
     *
     * <p>{@link TransitMode}를 그대로 쓰지 않는 이유: 시외 수단(TRAIN·EXPRESS_BUS·AIR)까지 담을 수
     * 있는 타입을 1단 switch에 넣으면 컴파일러가 그 분기를 요구하고, 실제로는 올 수 없는 값이라
     * 예외를 던지는 arm이 생긴다 — 그 순간 "여기 닿지 않는다"는 보장이 컴파일 시점에서 런타임으로
     * 내려앉는다. 시외 수단을 담을 수 없는 타입을 쓰면 그 보장이 타입으로 돌아온다.
     */
    private enum RoadMode {
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
    private record LegModes(boolean transit, List<RoadMode> road) {
    }

    /**
     * 1단(병렬) 결과.
     *
     * <p>{@code paths}를 그대로 들고 있는 이유: 시외 판정({@code pathType})·시내 후보 선정·
     * 도서 목적지 판정이 모두 원본을 봐야 한다. 미리 평탄화하면 2단에서 다시 호출하게 된다.
     */
    private record RoadResult(
            List<OdsayRouteResponse.Path> paths,
            boolean transitRequested,
            List<Candidate> roadCandidates
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
