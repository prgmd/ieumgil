package com.ssafy.ieumgil.domain.transit.service;

import com.ssafy.ieumgil.domain.block.entity.Block;
import com.ssafy.ieumgil.domain.block.repository.BlockRepository;
import com.ssafy.ieumgil.domain.place.dto.PlaceResDTO;
import com.ssafy.ieumgil.domain.place.service.PlaceQueryService;
import com.ssafy.ieumgil.domain.project.entity.Project;
import com.ssafy.ieumgil.domain.project.entity.TransportPref;
import com.ssafy.ieumgil.domain.project.exception.ProjectErrorCode;
import com.ssafy.ieumgil.domain.project.repository.ProjectRepository;
import com.ssafy.ieumgil.domain.transit.dto.TransitCandidateResDTO;
import com.ssafy.ieumgil.domain.transit.dto.TransitCandidateResDTO.Candidate;
import com.ssafy.ieumgil.domain.transit.dto.TransitCandidateResDTO.TransitMode;
import com.ssafy.ieumgil.domain.transit.dto.TransitResDTO;
import com.ssafy.ieumgil.domain.transit.exception.TransitErrorCode;
import com.ssafy.ieumgil.domain.transit.exception.TransitException;
import com.ssafy.ieumgil.domain.transit.util.Haversine;
import com.ssafy.ieumgil.global.exception.CustomException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
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
 * 이 서비스는 최대 20초({@link #OVERALL_TIMEOUT})까지 걸릴 수 있는 외부 API 호출 구간을 감싸는데,
 * 트랜잭션을 열어 두면 그동안 DB 커넥션을 붙잡아 몇 개의 동시 요청만으로 커넥션 풀이 고갈된다.
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

    private final BlockRepository blockRepository;
    private final ProjectRepository projectRepository;
    private final PlaceQueryService placeQueryService;
    private final PublicTransitQueryService publicTransitQueryService;
    private final FuelPriceProvider fuelPriceProvider;

    @Override
    public TransitCandidateResDTO.Result calculate(Long projectId, List<Long> blockIds) {
        Project project = projectRepository.findByIdAndDeletedAtIsNull(projectId)
                .orElseThrow(() -> new CustomException(ProjectErrorCode.PROJECT_NOT_FOUND));
        Map<Long, Block> blocks = loadBlocks(projectId, blockIds);
        TransportPref pref = project.getTransportPref();

        List<Pair> pairs = pairsOf(blockIds, blocks);
        Map<Leg, List<Candidate>> byLeg = calculateLegs(distinctLegsOf(pairs), pref);

        List<TransitCandidateResDTO.Segment> segments = pairs.stream()
                .map(pair -> {
                    List<Candidate> candidates = byLeg.get(legOf(pair.from(), pair.to()));
                    return TransitCandidateResDTO.Segment.builder()
                            .fromBlockId(pair.from().getId())
                            .toBlockId(pair.to().getId())
                            .defaultMode(defaultModeOf(candidates))
                            .candidates(candidates)
                            .build();
                })
                .toList();
        return TransitCandidateResDTO.Result.builder().segments(segments).build();
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
     */
    private Map<Leg, List<Candidate>> calculateLegs(Set<Leg> legs, TransportPref pref) {
        List<Leg> ordered = List.copyOf(legs);
        Semaphore permits = new Semaphore(MAX_CONCURRENT_CALLS);
        List<Callable<List<Candidate>>> tasks = ordered.stream()
                .map(leg -> (Callable<List<Candidate>>) () -> {
                    permits.acquire();
                    try {
                        return calculateLeg(leg, modesFor(pref, straightDistanceOf(leg)));
                    } finally {
                        permits.release();
                    }
                })
                .toList();

        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        try {
            List<Future<List<Candidate>>> futures =
                    executor.invokeAll(tasks, OVERALL_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            Map<Leg, List<Candidate>> byLeg = new HashMap<>();
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
    private List<Candidate> resultOf(Future<List<Candidate>> future, Leg leg, TransportPref pref) {
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

    private List<Candidate> unavailableFor(Leg leg, TransportPref pref) {
        return modesFor(pref, straightDistanceOf(leg)).stream()
                .map(Candidate::unavailable)
                .toList();
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
    private List<TransitMode> modesFor(TransportPref pref, double straightM) {
        // 5분 거리에 대중교통·택시를 물어봐야 답도 도보와 다르지 않다. 호출을 통째로 생략한다.
        if (straightM < NEAR_METERS) {
            return List.of(TransitMode.WALK);
        }

        List<TransitMode> modes = new ArrayList<>();
        // 프로젝트 생성 시 선호를 고르지 않을 수 있다(nullable). 대중교통이 더 보편적인 기본값이다.
        if (pref == TransportPref.CAR) {
            modes.add(TransitMode.CAR);
        } else {
            modes.add(TransitMode.TRANSIT);
        }
        modes.add(TransitMode.TAXI);
        // 먼 구간의 도보는 목록에서 빠진다 — available=false가 아니라 부재다.
        // 둘을 뭉개면 프론트가 "먼 것인가 API가 죽은 것인가"를 구분하지 못한다.
        if (straightM <= WALK_MAX_METERS) {
            modes.add(TransitMode.WALK);
        }
        return modes;
    }

    private List<Candidate> calculateLeg(Leg leg, List<TransitMode> modes) {
        // 택시와 자차는 같은 카카오 길찾기 응답을 나눠 쓴다 — 요금 계산만 다를 뿐 경로는 같다.
        PlaceResDTO.TaxiRoute driving = needsDriving(modes) ? callDriving(leg) : null;

        List<Candidate> candidates = new ArrayList<>();
        for (TransitMode mode : modes) {
            candidates.add(switch (mode) {
                case TRANSIT -> transitCandidate(leg);
                case TAXI -> taxiCandidate(driving);
                case CAR -> carCandidate(driving);
                case WALK -> walkCandidate(leg);
                // modesFor()는 시내 구간에서 이 넷만 넘긴다 — 시외 수단은 여기 닿지 않는다.
                case TRAIN, EXPRESS_BUS, AIR -> throw new IllegalStateException("시내 구간에 시외 수단: " + mode);
            });
        }
        return candidates;
    }

    /** 앞에서부터 살아있는 첫 후보가 기본이다. 전부 실패했으면 null — 프론트가 그 구간만 비워 둔다. */
    private TransitMode defaultModeOf(List<Candidate> candidates) {
        return candidates.stream()
                .filter(Candidate::available)
                .map(Candidate::mode)
                .findFirst()
                .orElse(null);
    }

    private boolean needsDriving(List<TransitMode> modes) {
        return modes.contains(TransitMode.TAXI) || modes.contains(TransitMode.CAR);
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

    private Candidate transitCandidate(Leg leg) {
        try {
            TransitResDTO.Route route = publicTransitQueryService
                    .getCombinedRoute(leg.fromLat(), leg.fromLng(), leg.toLat(), leg.toLng());
            return Candidate.builder()
                    .mode(TransitMode.TRANSIT)
                    .label(TransitMode.TRANSIT.label())
                    .available(true)
                    .durationMin(route.durationMin())
                    .fare(route.fare())
                    .fareConfidence(route.fareConfidence())
                    .intervalMin(route.intervalMin())
                    .distanceM(route.distanceM())
                    .build();
        } catch (RuntimeException e) {
            log.warn("대중교통 경로 조회 실패: leg={}", leg, e);
            return Candidate.unavailable(TransitMode.TRANSIT);
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
}
