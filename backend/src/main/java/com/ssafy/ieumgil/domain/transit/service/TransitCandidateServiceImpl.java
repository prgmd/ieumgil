package com.ssafy.ieumgil.domain.transit.service;

import com.ssafy.ieumgil.domain.block.entity.Block;
import com.ssafy.ieumgil.domain.block.repository.BlockRepository;
import com.ssafy.ieumgil.domain.place.dto.PlaceResDTO;
import com.ssafy.ieumgil.domain.place.service.PlaceQueryService;
import com.ssafy.ieumgil.domain.project.entity.Project;
import com.ssafy.ieumgil.domain.project.entity.TransportPref;
import com.ssafy.ieumgil.domain.project.exception.ProjectErrorCode;
import com.ssafy.ieumgil.domain.project.repository.ProjectRepository;
import com.ssafy.ieumgil.domain.transit.dto.OdsayRouteResponse;
import com.ssafy.ieumgil.domain.transit.dto.TransitCandidateResDTO;
import com.ssafy.ieumgil.domain.transit.dto.TransitCandidateResDTO.Candidate;
import com.ssafy.ieumgil.domain.transit.dto.TransitCandidateResDTO.CandidateStatus;
import com.ssafy.ieumgil.domain.transit.dto.TransitCandidateResDTO.TransitMode;
import com.ssafy.ieumgil.domain.transit.dto.TransitLegResDTO;
import com.ssafy.ieumgil.domain.transit.dto.TransitResDTO;
import com.ssafy.ieumgil.domain.transit.dto.TransitScheduleResDTO;
import com.ssafy.ieumgil.domain.transit.exception.OdsayNoRouteException;
import com.ssafy.ieumgil.domain.transit.exception.TransitErrorCode;
import com.ssafy.ieumgil.domain.transit.exception.TransitException;
import com.ssafy.ieumgil.domain.transit.util.BoardingMargin;
import com.ssafy.ieumgil.domain.transit.util.ConnectionPlanner;
import com.ssafy.ieumgil.domain.transit.util.Haversine;
import com.ssafy.ieumgil.domain.transit.util.LandReachability;
import com.ssafy.ieumgil.domain.transit.util.IntercityLegs;
import com.ssafy.ieumgil.domain.transit.util.StationIdBands;
import com.ssafy.ieumgil.global.exception.CustomException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
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
 * {@link #OVERALL_TIMEOUT}(20초)까지, 2단은 시외 구간마다의 시간표 조회로(예산은 요청 전체가 공유)
 * {@link #TIMETABLE_TIMEOUT}(20초)까지 걸릴 수 있다 — 두 단계는 순차이므로 <b>최악 40초</b>다.
 * 두 상한 모두 가상 스레드 {@code invokeAll}로 강제하며, 상한을 넘긴 조회는 "조회 실패"로 내려간다.
 *
 * <p>2단의 "시간표 조회"는 시간표 API 호출뿐 아니라 수단별 접근·이탈 경로 조회
 * ({@link #accessLegsOf})까지 포함한다 — 수단마다 승차·하차 지점이 달라 접근·이탈이 실제로
 * 다르므로, 시외 경로 하나가 door-to-door로 확정되기까지 {@code getCombinedRoutes}를 최대
 * 7회(1단의 본 leg 1회 + 최대 3개 수단 × 접근·이탈 2회) 부를 수 있다. 이 추가 호출은
 * 1단({@link #fetchRoadResults})의 {@link #distinctLegsOf} 중복 제거·
 * {@value #MAX_CONCURRENT_CALLS}개 세마포어를 거치지 않고, {@link #TIMETABLE_TIMEOUT} 예산을
 * 시간표 조회와 그대로 나눠 쓴다 — 이름은 "시간표 타임아웃"이지만 2단 전체(접근·이탈 포함)의
 * 예산이다. 이 증폭은 구조적이다(수단마다 접근 지점이 다르므로 캐시로 없앨 수 없다) —
 * 경로 캐시는 별도 과업이다.
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
    public TransitCandidateResDTO.Result calculate(Long projectId, List<Long> blockIds) {
        Project project = projectRepository.findByIdAndDeletedAtIsNull(projectId)
                .orElseThrow(() -> new CustomException(ProjectErrorCode.PROJECT_NOT_FOUND));
        List<Pair> pairs = pairsOf(blockIds, loadBlocks(projectId, blockIds));

        // 1단: 구간마다 시내 경로·자차·택시를 병렬로 모은다. 시외 여부는 여기서 받은 pathType으로 판정된다.
        Map<Leg, RoadResult> roadByLeg = fetchRoadResults(distinctLegsOf(pairs), project.getTransportPrefs());

        // 2단: 구간마다 독립적으로 기준 시각을 구해 시간표를 붙인다. 블록은 연속적이지 않다 —
        // resolveOverlaps가 블록 사이 공백을 그대로 보존하므로(BLK 이동 추가 칩의 근거), 구간의
        // 기준 시각은 앞 구간에서 누적한 값이 아니라 이 구간의 from 블록에 저장된 시각 그대로다
        // ({@link #baseMinutesOf}). 그래서 한 Day에 시외 구간이 여러 개여도 서로의 확정 여부와
        // 무관하게 각자 자기 시간표를 받는다 — 뒤 구간의 기준이 앞 구간이 고른 편에 좌우되지 않기
        // 때문이다.
        //
        // 시간표 조회 예산({@link #TIMETABLE_TIMEOUT})은 요청 전체가 공유한다 — 여기서 데드라인을
        // 한 번만 잡고 구간마다 "남은" 시간만 쓰게 한다. 구간마다 새 20초를 주면 여러 시외 구간을
        // 담은 요청에서 클래스 Javadoc의 "최악 40초" 계약이 깨진다.
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
                    segment = intercitySegmentWithoutTimetable(pair, road, SKIP_REASON_NO_START_TIME, multiPref);
                } else if (project.getStartDate() == null) {
                    // 여기서는 시작일이 있는지만 본다 — 실제 여행 날짜는 수단마다 다를 수 있어
                    // (자정 경계, referenceFor 참고) 수단별로 따로 계산한다. 시작일이 없을 때
                    // 오늘로 갈음하지 않는다 — 오늘 요일로 운행 편을 거르고 요일별 요금을 고르면
                    // 실제 여행일과 무관한 시간표를 timetableApplied=true·CONFIRMED로 내보내게
                    // 된다. 모른다는 사실을 그대로 내보내는 편이 낫다.
                    segment = intercitySegmentWithoutTimetable(pair, road, SKIP_REASON_NO_START_DATE, multiPref);
                } else {
                    Duration timetableBudget = remainingBudget(timetableDeadline);
                    if (timetableBudget.isZero()) {
                        // 다른 구간(또는 다른 Day)이 공유 예산을 이미 다 썼다.
                        segment = intercitySegmentWithoutTimetable(
                                pair, road, SKIP_REASON_TIMETABLE_BUDGET_EXHAUSTED, multiPref);
                    } else {
                        segment = intercitySegment(
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
     * 이 구간을 떠날 수 있는 시각(자정 기준 분). {@code from} 블록의 저장된 종료 시각이다.
     *
     * <p>앞 구간의 결과를 누적하지 않는다 — 블록은 연속적이지 않고 사이 공백이 실재하므로,
     * 이 구간이 볼 수 있는 진실은 {@code from} 블록 자신에 저장된 시각뿐이다. 시각 없는(느슨한)
     * 블록은 기준을 만들 수 없어 {@code null}이다.
     */
    private Integer baseMinutesOf(Block from) {
        LocalTime start = from.getStartTime();
        if (start == null) {
            return null;
        }
        return start.getHour() * 60 + start.getMinute() + from.getDurationMin();
    }

    /**
     * 수단별 출발 기준의 날짜와 시각. {@code base}(from 블록의 저장된 종료 시각, 자정 기준 분)에
     * 수단별 탑승 여유({@link BoardingMargin})를 더한 값 하나(절대 분)에서 날짜·시각을 함께
     * 뽑는다.
     *
     * <p>날짜와 시각을 각자 다른 값으로 계산하면 안 된다 — from 블록이 23:20에 끝나고 항공
     * 여유가 40분이면 절대 기준은 다음 날 00:00이다. 시각만 다음 날로 넘기고 날짜는 base
     * 하나만 보고 정하면(예: 오늘로 남으면), 오늘 이미 지나간 항공편이 "기준 이후"로 잘못
     * 통과해 버린다. 그래서 이 메서드가 날짜·시각을 항상 같은 절대값에서 함께 만든다.
     */
    private Reference referenceFor(LocalDate startDate, int dayNo, int base, TransitMode mode) {
        int absoluteMinutes = base + BoardingMargin.minutesFor(mode);
        LocalDate date = startDate.plusDays(dayNo - 1L + absoluteMinutes / 1440);
        int minuteOfDay = absoluteMinutes % 1440;
        LocalTime time = LocalTime.of(minuteOfDay / 60, minuteOfDay % 60);
        return new Reference(date, time);
    }

    /** {@link #referenceFor}가 함께 만들어 낸 날짜·시각 — 자정 경계에서 서로 어긋나지 않게 묶는다. */
    private record Reference(LocalDate date, LocalTime time) {
    }

    /**
     * 공유 데드라인까지 남은 시간(음수면 0으로 바닥 처리). Day마다 새 {@link #TIMETABLE_TIMEOUT}을
     * 주지 않고 이 값을 2단 호출({@link #intercityCandidates})의 실제 상한으로 쓴다 — 그래야 여러
     * Day를 담은 요청에서도 2단 전체가 {@link #TIMETABLE_TIMEOUT} 하나를 공유한다.
     */
    private Duration remainingBudget(Instant deadline) {
        Duration remaining = Duration.between(Instant.now(), deadline);
        return remaining.isNegative() ? Duration.ZERO : remaining;
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
     * <p>시간표 조회는 여기 없다. 기준 시각이 앞 구간의 결과에 달려 있어 병렬로 만들 수 없다.
     * Day마다 첫 시외 구간에서 한 번씩 불릴 수 있어 요청 하나에 여러 번 불릴 수 있지만,
     * 그 여러 호출이 {@link #TIMETABLE_TIMEOUT} 하나를 나눠 쓰므로({@link #remainingBudget})
     * 전체 상한은 그대로 유지된다.
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

        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        try {
            List<Future<RoadResult>> futures =
                    executor.invokeAll(tasks, OVERALL_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            Map<Leg, RoadResult> byLeg = new HashMap<>();
            for (int i = 0; i < ordered.size(); i++) {
                byLeg.put(ordered.get(i), resultOf(futures.get(i), ordered.get(i), prefs));
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
    private RoadResult resultOf(Future<RoadResult> future, Leg leg, List<TransportPref> prefs) {
        try {
            return future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("교통 후보 구간 조회 인터럽트: leg={}", leg);
            return unavailableFor(leg, prefs);
        } catch (CancellationException e) {
            log.warn("교통 후보 구간 조회 타임아웃 취소(20초): leg={}", leg);
            return unavailableFor(leg, prefs);
        } catch (ExecutionException e) {
            log.warn("교통 후보 구간 조회 실패: leg={}", leg, e.getCause());
            return unavailableFor(leg, prefs);
        }
    }

    /** 경로 목록이 비어 있으므로 시외로 판정되지 않는다 — 조회 실패는 시내 구간의 조회 실패로 나간다. */
    private RoadResult unavailableFor(Leg leg, List<TransportPref> prefs) {
        LegModes modes = modesFor(prefs, straightDistanceOf(leg));
        return new RoadResult(List.of(), modes.transit(),
                modes.road().stream().map(mode -> Candidate.lookupFailed(mode.mode())).toList(), false);
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
        RouteLookup lookup = modes.transit() || needsDriving
                ? routeLookupOf(leg)
                : new RouteLookup(List.of(), false);
        // 차로 갈 수 없는 목적지(시외 경로가 전부 항공)면 자차·택시를 아예 만들지 않는다. 후보를
        // 만들어 두고 status로 감추지 않는 이유: 프론트는 status와 무관하게 모든 후보를 그리므로
        // "조회 실패" 회색 줄이 남고, 그게 사용자가 신고한 증상이다. 도보는 그대로 둔다 —
        // 2km 임계로 이미 걸러지고, 육지와 이어진 섬(영도 등)까지 잘못 걸린다.
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
        return new RoadResult(lookup.paths(), modes.transit(), List.copyOf(roadCandidates), lookup.noRoute());
    }

    /** 시외 경로만 남긴다({@link #INTERCITY_PATH_TYPES}). 시내 구간이면 빈 목록이다 */
    private static List<OdsayRouteResponse.Path> intercityPathsOf(List<OdsayRouteResponse.Path> paths) {
        return paths.stream().filter(path -> INTERCITY_PATH_TYPES.contains(path.pathType())).toList();
    }

    /**
     * 1단의 대중교통 경로 조회. "조회에 실패해서 비었다"와 "ODsay가 경로가 없다고 답했다"를
     * 구분해서 들고 온다 — 앞은 재시도가 답이고 뒤는 몇 번을 물어도 같은 답이라 사용자가 할
     * 행동이 다르다({@link CandidateStatus#NO_ROUTE}). 빈 목록으로 뭉개면 그 구분이 사라진다.
     *
     * <p>어느 쪽이든 예외를 밖으로 던지지 않는다 — 한 수단이 죽었다고 구간 전체를 못 내면 안 된다.
     */
    private RouteLookup routeLookupOf(Leg leg) {
        try {
            List<OdsayRouteResponse.Path> paths = publicTransitQueryService
                    .getCombinedRoutes(leg.fromLat(), leg.fromLng(), leg.toLat(), leg.toLng());
            return new RouteLookup(paths, false);
        } catch (OdsayNoRouteException e) {
            log.info("ODsay가 경로를 주지 않는 구간: leg={}", leg);
            return new RouteLookup(List.of(), true);
        } catch (RuntimeException e) {
            log.warn("대중교통 경로 목록 조회 실패: leg={}", leg, e);
            return new RouteLookup(List.of(), false);
        }
    }

    /**
     * 대중교통 경로 목록. 실패든 "경로 없음"이든 빈 목록이다 — 접근·이탈 조회는 둘을 구분할
     * 이유가 없다(어느 쪽이든 그 수단 후보를 만들지 않는다). 구분이 필요한 1단은
     * {@link #routeLookupOf}를 쓴다.
     */
    private List<OdsayRouteResponse.Path> combinedRoutesFor(Leg leg) {
        return routeLookupOf(leg).paths();
    }

    /** 1단 경로 조회 결과. 빈 목록 하나로는 "조회 실패"와 "ODsay가 경로 없다고 답함"이 구분되지 않는다. */
    private record RouteLookup(List<OdsayRouteResponse.Path> paths, boolean noRoute) {
    }

    /** 블록 → 승차 지점(접근). {@link IntercityLegs.Point}는 x=경도·y=위도다 */
    private List<OdsayRouteResponse.Path> combinedRoutesFor(Block from, IntercityLegs.Point to) {
        return combinedRoutesFor(new Leg(
                from.getLat().doubleValue(), from.getLng().doubleValue(), to.y(), to.x()));
    }

    /** 하차 지점 → 블록(이탈). {@link IntercityLegs.Point}는 x=경도·y=위도다 */
    private List<OdsayRouteResponse.Path> combinedRoutesFor(IntercityLegs.Point from, Block to) {
        return combinedRoutesFor(new Leg(
                from.y(), from.x(), to.getLat().doubleValue(), to.getLng().doubleValue()));
    }

    /**
     * 블록 → 승차 지점, 하차 지점 → 블록. 시외 경로엔 이 leg가 없어(실측 537건 전부 0) 따로 부른다.
     *
     * <p>접근·이탈 중 하나라도 경로가 없으면(빈 목록) {@code empty}다 — 0분으로 추측하지 않는다.
     * 호출자가 이 empty를 "그 수단 후보를 만들지 않는다"로 읽고 {@code log.warn}으로 남긴다.
     * package-private인 이유는 {@code TransitCandidateServiceImplTest}가 직접 부르기 때문이다
     * ({@link #TIMETABLE_TIMEOUT}과 같은 이유).
     */
    Optional<AccessLegs> accessLegsOf(Pair pair, IntercityLegs legs) {
        IntercityLegs.Point boarding = legs.boardingPoint();
        IntercityLegs.Point alighting = legs.alightingPoint();

        List<OdsayRouteResponse.Path> access = combinedRoutesFor(pair.from(), boarding);
        if (access.isEmpty()) {
            log.warn("접근 경로 없음: fromBlockId={}, boarding={}", pair.from().getId(), boarding);
            return Optional.empty();
        }
        List<OdsayRouteResponse.Path> egress = combinedRoutesFor(alighting, pair.to());
        if (egress.isEmpty()) {
            log.warn("이탈 경로 없음: alighting={}, toBlockId={}", alighting, pair.to().getId());
            return Optional.empty();
        }

        OdsayRouteResponse.Path accessPath = access.get(0);
        OdsayRouteResponse.Path egressPath = egress.get(0);
        return Optional.of(new AccessLegs(
                TransitLegResDTO.fromSubPaths(accessPath.subPath()),
                accessPath.info().totalTime(),
                accessPath.info().payment(),
                TransitLegResDTO.fromSubPaths(egressPath.subPath()),
                egressPath.info().totalTime(),
                egressPath.info().payment()));
    }

    /**
     * 시외 구간. 세 수단을 각각 만든다 — 서울→부산에서 기차 59,800원 2시간 37분과
     * 고속버스 39,700원 4시간은 사용자가 실제로 저울질하는 대안이라 하나로 뭉치면 그 선택이 사라진다.
     */
    private TransitCandidateResDTO.Segment intercitySegment(
            Pair pair, RoadResult road, int base, LocalDate startDate, int dayNo, Duration timetableBudget,
            boolean multiPref) {
        String from = road.firstStartStation();
        String to = road.lastEndStation();
        Map<TransitMode, IntercityLegs> legsByMode = IntercityLegs.pick(road.paths());

        List<Candidate> candidates = new ArrayList<>();
        if (legsByMode.isEmpty()) {
            // 어떤 leg의 역 ID도 알려진 대역({@link StationIdBands})에 들지 않았다. 추측하지
            // 않고 세 수단 모두 조회 실패로 낸다.
            log.warn("시외 경로에서 역 ID 대역을 판별할 수 없다: from={}, to={}", from, to);
            INTERCITY_MODES.forEach(mode -> candidates.add(Candidate.lookupFailed(mode)));
        } else {
            candidates.addAll(intercityCandidates(legsByMode, pair, base, startDate, dayNo, timetableBudget, from, to)
                    .stream().filter(Objects::nonNull).toList());
        }
        candidates.addAll(road.roadCandidates());   // 자차·택시. 1단에서 이미 만들어졌다

        return TransitCandidateResDTO.Segment.builder()
                .fromBlockId(pair.from().getId())
                .toBlockId(pair.to().getId())
                .intercity(true)
                .timetableApplied(true)
                .defaultMode(multiPref ? null : defaultModeOf(candidates))
                .candidates(candidates)
                .build();
    }

    /**
     * 시간표를 적용하지 않는 시외 구간.
     *
     * <p>from 블록에 시각이 없거나, 프로젝트 시작일을 모르거나, 시간표 조회 예산이 이미
     * 소진됐을 때 여기로 온다. 조용히 틀린 시각을 주는 대신 이유를 남겨 프론트가 안내하게 한다.
     *
     * <p>그래도 <b>수단 슬롯은 남긴다</b> — 서울→부산→제주에서 뒤 구간만 항공이 사라지면
     * 제주에 배로 가라는 말이 된다. 편 목록만 비고({@code departures=[]}) 수단은 그대로다.
     * 조회를 안 한 것이지 실패한 것이 아니므로 {@code status=OK}다.
     *
     * <p>ODsay가 이 수단의 경로를 줬으면({@link IntercityLegs#pick}에 그 수단이 있으면) 시간표는
     * 못 붙여도 ODsay 경로 자신의 시각·leg은 있다 — {@code durationMin}은 그 경로의
     * {@code info().totalTime()}, {@code legs}는 그 경로의 subPath 그대로 채운다. 시간표
     * 미적용이 "아무것도 모른다"는 뜻은 아니다. ODsay조차 이 수단의 경로를 안 줬으면(맵에 없음)
     * 채울 근거가 없어 편 목록·leg 모두 비운다.
     */
    private TransitCandidateResDTO.Segment intercitySegmentWithoutTimetable(
            Pair pair, RoadResult road, String reason, boolean multiPref) {
        Map<TransitMode, IntercityLegs> legsByMode = IntercityLegs.pick(road.paths());
        List<Candidate> candidates = new ArrayList<>();
        for (TransitMode mode : INTERCITY_MODES) {
            candidates.add(noTimetableCandidate(
                    mode, legsByMode.get(mode), road.firstStartStation(), road.lastEndStation()));
        }
        candidates.addAll(road.roadCandidates());

        return TransitCandidateResDTO.Segment.builder()
                .fromBlockId(pair.from().getId())
                .toBlockId(pair.to().getId())
                .intercity(true)
                .timetableApplied(false)
                .timetableSkipReason(reason)
                .defaultMode(multiPref ? null : defaultModeOf(candidates))
                .candidates(candidates)
                .build();
    }

    /**
     * 시간표를 적용하지 않는 시외 후보 하나. {@code legs}가 없으면(ODsay 응답에 이 수단의 경로
     * 자체가 없음) {@link #departureCandidate}의 빈 편 목록 분기와 같은 모양으로 낸다 — 지어낼
     * 근거가 없다. 있으면 그 경로 자신의 시각·leg을 채운다(브리프 상태 분기 3:
     * "시간표 미적용 → OK, ODsay 시외 leg 시간 사용"). 접근·이탈은 채우지 않는다 — 그건 시간표가
     * 붙어야 기준 시각을 만들 수 있는 door-to-door({@link #doorToDoorCandidate})의 몫이다.
     */
    private Candidate noTimetableCandidate(TransitMode mode, IntercityLegs legs, String from, String to) {
        if (legs == null) {
            return departureCandidate(mode, List.of(), from, to, 0);
        }
        return Candidate.builder()
                .mode(mode)
                .label(mode.label())
                .status(CandidateStatus.OK)
                .durationMin(legs.path().info().totalTime())
                .fareConfidence(TransitResDTO.FareConfidence.UNKNOWN)
                .transferCount(legs.legs().size() - 1)
                .legs(TransitLegResDTO.fromSubPaths(legs.legs()))
                .departures(List.of())
                .build();
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
        if (road.transitRequested()) {
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

    /**
     * 세 수단의 시간표를 가상 스레드로 동시에 부른다.
     *
     * <p>세 수단 모두 subPath가 준 역 ID를 이름 검색 없이 그대로 시간표 API에 넘기므로
     * 수단마다 시간표 조회 1회뿐이다(실측 89/89 성공 — {@link #timetableCandidateFor} 참고).
     * 직렬로 두면 한 요청이 read-timeout 15초 × 3회를 서블릿 스레드에서 그대로 뒤집어쓴다.
     * 1단({@link #fetchRoadResults})과 같은 방식으로 동시에 부른다.
     *
     * <p>상한은 {@link #TIMETABLE_TIMEOUT} 그 자체가 아니라 호출한 쪽이 넘겨준 {@code budget}이다
     * — 여러 시외 구간을 담은 요청이면 이 메서드가 구간마다 다시 불리는데, 예산은 요청 전체가
     * {@link #remainingBudget}로 공유하므로 두 번째 이후 구간은 남은 시간만큼만 받는다(항상
     * 0보다 크다 — 호출자가 0이면 아예 부르지 않고 예산 소진 사유로 건너뛴다).
     *
     * <p>세마포어를 걸지 않는 이유: 2단은 1단이 끝난 뒤에 돌고 동시 호출이 세 개뿐이라
     * {@value #MAX_CONCURRENT_CALLS}개 상한 안이다.
     *
     * <p>순서는 {@link #INTERCITY_MODES} 그대로다 — 후보 순서가 곧 화면 순서다.
     *
     * <p>{@code base}(from 블록의 저장된 종료 시각, 자정 기준 분)는 세 수단에 그대로 공유되지만,
     * 각 수단이 실제로 거르는 기준 날짜·시각은 여기서 다시 계산하지 않는다 — {@link #referenceFor}가
     * 수단별 탑승 여유({@link BoardingMargin})를 각자 더하므로 기차·고속버스·항공의 기준이 서로 다르고,
     * 자정을 넘기면 날짜도 수단마다 달라질 수 있다.
     *
     * @param legsByMode 수단별 대표 시외 leg({@link IntercityLegs#pick}). ODsay 응답에 그 수단의
     *                   경로가 없거나 역 ID 대역을 판별할 수 없으면 그 수단은 이 맵에 없다 —
     *                   {@link #candidateFor}가 그 경우를 조회 실패로 낸다.
     */
    private List<Candidate> intercityCandidates(
            Map<TransitMode, IntercityLegs> legsByMode, Pair pair, int base, LocalDate startDate, int dayNo,
            Duration budget, String from, String to) {
        List<Callable<Candidate>> tasks = INTERCITY_MODES.stream()
                .<Callable<Candidate>>map(mode -> () ->
                        candidateFor(mode, legsByMode.get(mode), pair, base, startDate, dayNo, from, to))
                .toList();

        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        try {
            List<Future<Candidate>> futures =
                    executor.invokeAll(tasks, budget.toMillis(), TimeUnit.MILLISECONDS);
            List<Candidate> candidates = new ArrayList<>();
            for (int i = 0; i < INTERCITY_MODES.size(); i++) {
                candidates.add(candidateOf(futures.get(i), INTERCITY_MODES.get(i), budget));
            }
            return candidates;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("시외 시간표 조회 인터럽트: from={}, to={}", from, to);
            return INTERCITY_MODES.stream().map(Candidate::lookupFailed).toList();
        } finally {
            executor.shutdownNow();
        }
    }

    /** 타임아웃·예외로 끝난 수단만 조회 실패로 내려간다 — 나머지 두 수단까지 잃지 않는다. */
    private Candidate candidateOf(Future<Candidate> future, TransitMode mode, Duration budget) {
        try {
            return future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("시외 시간표 조회 인터럽트: mode={}", mode);
            return Candidate.lookupFailed(mode);
        } catch (CancellationException e) {
            log.warn("시외 시간표 조회 타임아웃 취소({}초): mode={}", budget.toSeconds(), mode);
            return Candidate.lookupFailed(mode);
        } catch (ExecutionException e) {
            log.warn("시외 시간표 조회 실패: mode={}", mode, e.getCause());
            return Candidate.lookupFailed(mode);
        }
    }

    /**
     * 수단별 시외 후보. {@code legs}가 없으면(=ODsay 응답에 이 수단의 경로가 없거나 첫 leg의
     * 역 ID 대역을 판별할 수 없음) 조회 자체를 시도하지 않고 조회 실패로 낸다 — 이름 검색으로
     * 대체하지 않는다.
     *
     * <p>환승 경로(leg 2개)는 첫 leg 후보를 만든 뒤 {@link #withConnections}가 두 번째 leg
     * 시간표를 붙인다. 첫 leg 조회 자체가 실패했으면(조회 실패) 두 번째 leg를 붙일 수 없으므로
     * 그대로 반환한다.
     *
     * <p>접근·이탈 경로({@link #accessLegsOf})부터 구한다 — 첫 leg의 기준 시각 자체가
     * 접근 소요를 반영해야 하기 때문이다({@link #referenceFor}). 접근 경로가 없으면(조회
     * 실패·경로 없음) 시간표를 조회해 보지도 않고 {@code null}을 반환한다 — 그 수단 후보를
     * 만들지 않는다(접근시간을 0으로 추측하지 않는다). 호출자({@link #intercityCandidates})가
     * 이 {@code null}을 걸러낸다.
     *
     * <p>{@code mode}(대표 수단)는 후보의 <b>정체</b>일 뿐이다 — {@link IntercityLegs}가 목적지에
     * 도달하는 마지막 leg에서 뽑으므로 {@code pathType 20}에서는 첫 leg의 수단과 다르다. 그래서
     * 첫 leg의 시간표·탑승 여유는 대표 수단이 아니라 <b>그 leg 자신의 대역</b>으로 판별한
     * {@code boardingMode}를 쓴다({@link #withConnections}가 두 번째 leg에 하는 것과 대칭이다) —
     * 대표 수단을 넘기면 기차 역 ID가 항공 시간표 API로 가고, 기차편이 항공 여유(40분) 기준으로
     * 걸러진다.
     */
    private Candidate candidateFor(
            TransitMode mode, IntercityLegs legs, Pair pair, int base, LocalDate startDate, int dayNo,
            String from, String to) {
        if (legs == null) {
            return Candidate.lookupFailed(mode);
        }
        Optional<TransitMode> boardingMode = modeOfLeg(legs.legs().get(0));
        if (boardingMode.isEmpty()) {
            log.warn("첫 leg의 역 ID 대역을 판별할 수 없다: mode={}", mode);
            return Candidate.lookupFailed(mode);
        }
        Optional<AccessLegs> access = accessLegsOf(pair, legs);
        if (access.isEmpty()) {
            return null;
        }
        int accessArrival = base + access.get().accessMin();
        Candidate firstLegCandidate = timetableCandidateFor(
                boardingMode.get(), legs.legs().get(0), accessArrival, startDate, dayNo, from, to);
        if (firstLegCandidate.status() != CandidateStatus.OK) {
            return firstLegCandidate;
        }
        Candidate legCandidate = legs.isTransfer()
                ? withConnections(mode, legs, firstLegCandidate, base, startDate, dayNo, from, to)
                : firstLegCandidate;
        if (legCandidate.status() != CandidateStatus.OK) {
            return legCandidate;
        }
        return doorToDoorCandidate(
                mode, boardingMode.get(), legs, access.get(), legCandidate, base, startDate, dayNo);
    }

    /**
     * leg 하나의 수단. 그 leg의 출발역 ID 대역으로 판별한다({@link StationIdBands#modeOf}).
     * 역 ID가 없거나 대역을 모르면 empty다 — 추측하지 않는다.
     */
    private Optional<TransitMode> modeOfLeg(OdsayRouteResponse.SubPath leg) {
        return leg.startID() == null ? Optional.empty() : StationIdBands.modeOf(leg.startID());
    }

    /**
     * 접근·대기·시외(환승 포함)·이탈을 door-to-door로 합친 최종 후보.
     *
     * <p>{@code legCandidate}는 시간표가 적용된 leg 후보({@link #timetableCandidateFor} 또는
     * 환승이면 {@link #withConnections}의 결과)다 — 대표 편(첫 편)·연결편은 이미 정해져 있다.
     * 여기서는 그 편에 {@code waitMin}을 채우고, top-level {@code durationMin}·{@code fare}를
     * 접근·이탈까지 반영한 door-to-door 값으로 다시 계산한다.
     *
     * <p>편이 하나도 없으면(막차 지남) {@code status=NO_SERVICE}다 — 접근 경로 조회와 시간표
     * 조회 자체는 성공했으니 {@code LOOKUP_FAILED}가 아니다. 그래도 {@code legs}·{@code fare}·
     * {@code accessMin}·{@code egressMin}·{@code referenceAt}은 채운다 — 그 값들은 특정 편에
     * 좌우되지 않는 구조적인 값이다. {@code durationMin}만 편이 있어야 계산되므로 null이다.
     *
     * @param mode         후보의 정체가 되는 대표 수단(목적지에 도달하는 수단)
     * @param boardingMode 실제로 탑승하는 첫 leg의 수단. {@code referenceAt}은 이 수단의 탑승
     *                     여유로 계산한다 — 그 값이 곧 첫 leg 편을 걸러낸 기준이므로 대표 수단의
     *                     여유를 쓰면 기차+항공 경로에서 화면 기준 시각과 실제 기준이 어긋난다
     */
    private Candidate doorToDoorCandidate(
            TransitMode mode, TransitMode boardingMode, IntercityLegs legs, AccessLegs access,
            Candidate legCandidate, int base, LocalDate startDate, int dayNo) {
        List<TransitLegResDTO.Leg> allLegs = new ArrayList<>();
        allLegs.addAll(access.access());
        allLegs.addAll(TransitLegResDTO.fromSubPaths(legs.legs()));
        allLegs.addAll(access.egress());

        int transferCount = vehicleLegCountOf(allLegs) - 1;
        Integer fare = fareOf(access, legs);
        TransitResDTO.FareConfidence fareConfidence = fareConfidenceOf(access, legs);
        String referenceAt =
                referenceFor(startDate, dayNo, base + access.accessMin(), boardingMode).time().format(HHMM);

        Candidate.CandidateBuilder builder = Candidate.builder()
                .mode(mode)
                .label(mode.label())
                .fare(fare)
                .fareConfidence(fareConfidence)
                .transferCount(transferCount)
                .legs(allLegs)
                .accessMin(access.accessMin())
                .egressMin(access.egressMin())
                .referenceAt(referenceAt);

        int accessArrivalMinuteOfDay = (base + access.accessMin()) % 1440;
        List<TransitCandidateResDTO.Departure> withWait = legCandidate.departures().stream()
                .map(departure -> departure.toBuilder()
                        .waitMin(waitMinutesOf(departure, accessArrivalMinuteOfDay))
                        .build())
                .toList();

        if (withWait.isEmpty()) {
            return builder.status(CandidateStatus.NO_SERVICE).departures(List.of()).build();
        }
        TransitCandidateResDTO.Departure first = withWait.get(0);
        return builder.status(CandidateStatus.OK)
                .durationMin(doorToDoorDurationOf(first, access.egressMin(), base % 1440))
                .departures(withWait)
                .build();
    }

    /** 접근·이탈 leg를 뺀 실제 탈것 leg 수. 도보는 "환승"으로 세지 않는다({@link TransitLegResDTO.LegType#WALK}) */
    private int vehicleLegCountOf(List<TransitLegResDTO.Leg> legs) {
        return (int) legs.stream().filter(leg -> leg.type() != TransitLegResDTO.LegType.WALK).count();
    }

    /**
     * door-to-door 요금. 세 조각(접근 payment·시외 totalPayment·이탈 payment) 중 하나라도 없으면
     * null이다 — 0으로 채워 더하지 않는다({@link #fareConfidenceOf}와 같은 근거).
     */
    private Integer fareOf(AccessLegs access, IntercityLegs legs) {
        Integer accessFare = access.accessFare();
        Integer intercityFare = legs.path().info().totalPayment();
        Integer egressFare = access.egressFare();
        if (accessFare == null || intercityFare == null || egressFare == null) {
            return null;
        }
        return accessFare + intercityFare + egressFare;
    }

    /** 세 조각이 모두 있어야 CONFIRMED다. 하나라도 없으면 UNKNOWN — 0으로 채우고 CONFIRMED를 붙이지 않는다. */
    private TransitResDTO.FareConfidence fareConfidenceOf(AccessLegs access, IntercityLegs legs) {
        boolean allPresent = access.accessFare() != null
                && legs.path().info().totalPayment() != null
                && access.egressFare() != null;
        return allPresent ? TransitResDTO.FareConfidence.CONFIRMED : TransitResDTO.FareConfidence.UNKNOWN;
    }

    /** 접근 도착({@code base+accessMin}) → 이 편 출발까지 대기(분). 자정을 넘기면 다음 날로 넘어간다 */
    private int waitMinutesOf(TransitCandidateResDTO.Departure departure, int accessArrivalMinuteOfDay) {
        int wait = minutesOf(departure.departureAt()) - accessArrivalMinuteOfDay;
        return wait < 0 ? wait + 1440 : wait;
    }

    /**
     * door-to-door 소요(분) = (마지막 도착시각 − base) + egressMin. 환승이면 연결편의 도착시각을
     * 쓴다. 도착시각을 모르면(고속버스인데 소요시간마저 없음) null이다 — 지어내지 않는다.
     */
    private Integer doorToDoorDurationOf(TransitCandidateResDTO.Departure first, int egressMin, int baseMinuteOfDay) {
        String lastArrivalAt = first.connection() != null ? first.connection().arrivalAt() : first.arrivalAt();
        if (lastArrivalAt == null) {
            return null;
        }
        int elapsed = minutesOf(lastArrivalAt) - baseMinuteOfDay;
        if (elapsed < 0) {
            elapsed += 1440;
        }
        return elapsed + egressMin;
    }

    private int minutesOf(String hhmm) {
        LocalTime time = LocalTime.parse(hhmm);
        return time.getHour() * 60 + time.getMinute();
    }

    /**
     * 환승 경로의 두 번째 leg 시간표를 <b>한 번만</b> 불러 첫 leg의 편마다 연결편을 붙인다.
     *
     * <p>두 번째 leg의 수단은 첫 leg과 같다고 가정하지 않고 다시 판별한다({@link #modeOfLeg}) —
     * {@code pathType 20}처럼 두 leg가 서로 다른 수단(기차→항공 등)일 수 있어서다. 판별할 수
     * 없거나 두 번째 leg 조회 자체가 실패하면 추측하지 않고 조회 실패로 낸다 — 첫 leg만 있는
     * 반쪽 경로를 직통처럼 내보내지 않는다.
     */
    private Candidate withConnections(
            TransitMode mode, IntercityLegs legs, Candidate firstLegCandidate,
            int base, LocalDate startDate, int dayNo, String from, String to) {
        Optional<TransitMode> secondMode = modeOfLeg(legs.legs().get(1));
        if (secondMode.isEmpty()) {
            log.warn("환승 두 번째 leg의 역 ID 대역을 판별할 수 없다: mode={}", mode);
            return Candidate.lookupFailed(mode);
        }
        Candidate secondLegCandidate =
                timetableCandidateFor(secondMode.get(), legs.legs().get(1), base, startDate, dayNo, from, to);
        if (secondLegCandidate.status() != CandidateStatus.OK) {
            return Candidate.lookupFailed(mode);
        }
        List<TransitCandidateResDTO.Departure> connected = ConnectionPlanner.connect(
                firstLegCandidate.departures(), secondLegCandidate.departures(), secondMode.get());
        List<TransitCandidateResDTO.Departure> withStations =
                attachTransferStations(connected, legs.legs().get(1));
        return departureCandidate(mode, withStations, from, to, legs.legs().size() - 1);
    }

    /**
     * 연결편에 환승 지점 이름을 붙인다. {@link ConnectionPlanner}는 순수 함수라 역 이름을 모른다 —
     * 두 leg를 다 아는 이 서비스가 두 번째 leg의 {@code SubPath}에서 이름을 가져와 채운다.
     * 이름이 없으면(null) 지어내지 않고 그대로 null로 둔다.
     */
    private List<TransitCandidateResDTO.Departure> attachTransferStations(
            List<TransitCandidateResDTO.Departure> departures, OdsayRouteResponse.SubPath secondLeg) {
        return departures.stream().map(departure -> withTransferStations(departure, secondLeg)).toList();
    }

    private TransitCandidateResDTO.Departure withTransferStations(
            TransitCandidateResDTO.Departure departure, OdsayRouteResponse.SubPath secondLeg) {
        TransitCandidateResDTO.Connection connection = departure.connection();
        TransitCandidateResDTO.Connection withStations = TransitCandidateResDTO.Connection.builder()
                .name(connection.name())
                .grade(connection.grade())
                .departureAt(connection.departureAt())
                .arrivalAt(connection.arrivalAt())
                .durationMin(connection.durationMin())
                .fare(connection.fare())
                .transferMin(connection.transferMin())
                .fromStation(secondLeg.startName())
                .toStation(secondLeg.endName())
                .build();
        return departure.toBuilder().connection(withStations).build();
    }

    /**
     * 시외 leg 하나의 시간표 후보. {@code leg}의 {@code startID}/{@code endID}를 이름 검색 없이
     * 그대로 시간표 API에 넘긴다 — 실측 89/89 성공(기차 35/35, 고속·시외버스 35/35, 항공 19/19).
     * 고속버스·시외버스는 {@code trafficType}이 달라도 같은 엔드포인트({@code searchInterBusSchedule},
     * {@link TransitScheduleQueryService#getIntercityBusSchedule})를 쓴다 — {@code mode}가 이미
     * {@link StationIdBands}로 둘을 하나(EXPRESS_BUS)로 합쳐 판별해 뒀으므로 여기서 다시 나누지 않는다.
     *
     * <p>package-private인 이유는 두 가지다. 첫째 {@code TransitCandidateServiceImplTest}가 직접
     * 부른다({@link #TIMETABLE_TIMEOUT}과 같은 이유). 둘째, 이 메서드는 leg 하나만 안다 — 환승
     * 경로(leg 2개)에서는 {@link #withConnections}가 이 메서드를 첫 leg·두 번째 leg에 각각 한
     * 번씩 불러 두 후보를 만들고, {@link ConnectionPlanner}로 이어붙인다.
     */
    Candidate timetableCandidateFor(
            TransitMode mode, OdsayRouteResponse.SubPath leg, int base, LocalDate startDate, int dayNo,
            String from, String to) {
        try {
            Reference reference = referenceFor(startDate, dayNo, base, mode);
            List<TransitCandidateResDTO.Departure> all = switch (mode) {
                case TRAIN -> transitScheduleQueryService
                        .getTrainSchedule(leg.startID(), leg.endID(), reference.date()).stream()
                        .map(this::toTrainDeparture).toList();
                case EXPRESS_BUS -> transitScheduleQueryService
                        .getIntercityBusSchedule(leg.startID(), leg.endID(), reference.date()).stream()
                        .map(this::toBusDeparture).toList();
                case AIR -> transitScheduleQueryService
                        .getFlightSchedule(leg.startID(), leg.endID(), reference.date()).stream()
                        .map(this::toFlightDeparture).toList();
                default -> throw new IllegalStateException("시외 시간표를 지원하지 않는 수단: " + mode);
            };
            // 항공은 요금이 없어 최저가 축을 쓸 수 없다(fareAware=false) — 시각순 3편이다.
            boolean fareAware = mode != TransitMode.AIR;
            return departureCandidate(mode,
                    DepartureSelector.selectThree(afterReference(all, reference.time()), fareAware), from, to, 0);
        } catch (RuntimeException e) {
            log.warn("{} 시간표 조회 실패: startId={}, endId={}", mode.label(), leg.startID(), leg.endID(), e);
            return Candidate.lookupFailed(mode);
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
     * <p>편이 없어도 {@code status=OK}다 — 조회는 성공했고 "그 시각 이후에는 없다"가
     * 유효한 답이다. {@code status=LOOKUP_FAILED}는 조회 실패에만 쓴다(기존 규칙).
     *
     * <p>편이 없으면 leg도 없다. 탈 편이 정해지지 않았는데 구간을 그리면 소요시간을 0분으로
     * 채우게 되고("즉시 도착"), 역 이름조차 없는 경우 {@code from}·{@code to}가 null인 구간이 남는다.
     *
     * @param transferCount 환승 횟수. 직통은 0, 환승 경로는 {@code legs.size() - 1}이다
     *                      ({@link #withConnections}). 대표값(durationMin·fare·legs)은 여전히
     *                      첫 편 하나에서만 온다 — door-to-door 합산은 이 메서드의 몫이 아니다.
     */
    private Candidate departureCandidate(
            TransitMode mode, List<TransitCandidateResDTO.Departure> selected, String from, String to,
            int transferCount) {
        if (selected.isEmpty()) {
            return Candidate.builder()
                    .mode(mode)
                    .label(mode.label())
                    .status(CandidateStatus.OK)
                    .fareConfidence(TransitResDTO.FareConfidence.UNKNOWN)
                    .transferCount(transferCount)
                    .legs(List.of())
                    .departures(List.of())
                    .build();
        }

        TransitCandidateResDTO.Departure first = selected.get(0);
        return Candidate.builder()
                .mode(mode)
                .label(mode.label())
                .status(CandidateStatus.OK)
                .durationMin(first.durationMin())
                .fare(first.fare())
                .fareConfidence(first.fareConfidence())
                .transferCount(transferCount)
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
     * <p>탈 수 있는 편이 없는 시외 후보는 건너뛴다. 조회는 성공했으니 {@code status=OK}지만
     * ({@link #departureCandidate}), 고를 편이 없는 수단을 기본으로 내밀 수는 없다.
     */
    private TransitMode defaultModeOf(List<Candidate> candidates) {
        return candidates.stream()
                .filter(candidate -> candidate.status() == CandidateStatus.OK)
                .filter(candidate -> candidate.departures() == null || !candidate.departures().isEmpty())
                .map(Candidate::mode)
                .findFirst()
                .orElse(null);
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
     * 호출이 모두 성공했다는 뜻이다({@link #accessLegsOf}가 실패를 이미 {@code Optional.empty()}로
     * 걸러낸 뒤에만 만들어진다) — 그 안에서는 0으로 추측할 자리가 없다. {@code accessFare}·
     * {@code egressFare}는 Integer다 — 시내 경로의 {@code payment} 자체가 nullable이라
     * ({@link OdsayRouteResponse.Info#payment}) 이 값도 그렇다.
     */
    record AccessLegs(
            List<TransitLegResDTO.Leg> access, int accessMin, Integer accessFare,
            List<TransitLegResDTO.Leg> egress, int egressMin, Integer egressFare) {
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
     * @param noRoute ODsay가 "경로가 없다"고 답했는지({@link OdsayNoRouteException}).
     *                {@code paths}가 비어 있다는 사실만으로는 조회 실패와 구분되지 않아 따로 든다.
     */
    private record RoadResult(
            List<OdsayRouteResponse.Path> paths,
            boolean transitRequested,
            List<Candidate> roadCandidates,
            boolean noRoute
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
