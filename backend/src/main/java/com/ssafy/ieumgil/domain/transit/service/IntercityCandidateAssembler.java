package com.ssafy.ieumgil.domain.transit.service;

import com.ssafy.ieumgil.domain.place.dto.PlaceResDTO;
import com.ssafy.ieumgil.domain.place.service.PlaceQueryService;
import com.ssafy.ieumgil.domain.transit.dto.OdsayRouteResponse;
import com.ssafy.ieumgil.domain.transit.dto.TransitCandidateResDTO;
import com.ssafy.ieumgil.domain.transit.dto.TransitCandidateResDTO.Candidate;
import com.ssafy.ieumgil.domain.transit.dto.TransitCandidateResDTO.CandidateStatus;
import com.ssafy.ieumgil.domain.transit.dto.TransitCandidateResDTO.TransitMode;
import com.ssafy.ieumgil.domain.transit.dto.TransitLegResDTO;
import com.ssafy.ieumgil.domain.transit.dto.TransitResDTO;
import com.ssafy.ieumgil.domain.transit.dto.TransitScheduleResDTO;
import com.ssafy.ieumgil.domain.transit.service.TransitCandidateServiceImpl.AccessLegs;
import com.ssafy.ieumgil.domain.transit.service.TransitCandidateServiceImpl.Leg;
import com.ssafy.ieumgil.domain.transit.service.TransitCandidateServiceImpl.Pair;
import com.ssafy.ieumgil.domain.transit.service.TransitCandidateServiceImpl.RoadResult;
import com.ssafy.ieumgil.domain.transit.util.BoardingMargin;
import com.ssafy.ieumgil.domain.transit.util.ConnectionPlanner;
import com.ssafy.ieumgil.domain.transit.util.IntercityLabel;
import com.ssafy.ieumgil.domain.transit.util.IntercityLegs;
import com.ssafy.ieumgil.domain.transit.util.OdsayClock;
import com.ssafy.ieumgil.domain.transit.util.ParallelInvoker;
import com.ssafy.ieumgil.domain.transit.util.StationIdBands;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Callable;

/**
 * 시외 구간 하나를 door-to-door 후보로 조립하는 2단 로직.
 *
 * <p>{@code TransitCandidateServiceImpl}이 1단(구간별 경로 조회)에서 시외로 판정한 구간을 받아,
 * 접근·대기·시외(환승 포함)·이탈을 각각 조회해 기차·고속버스·항공 후보로 만든다. 서비스가
 * 1418줄 god class로 커진 주범이라 별도 클래스로 분리했다 — 서비스는 이제 오케스트레이션과
 * 시내·도로 후보만 맡고, 시외 조립은 여기로 위임한다.
 *
 * <p>값 타입({@link Pair}·{@link AccessLegs}·{@link Leg}·{@link RoadResult})은 여전히
 * {@code TransitCandidateServiceImpl}의 중첩 타입이다 — 그 서비스의 단위 테스트가
 * {@code TransitCandidateServiceImpl.Pair}·{@code .AccessLegs}를 직접 참조하므로 옮기지 못한다.
 * 이 조립기는 {@code @InjectMocks} 시그니처를 지키기 위해 서비스가 직접 조립한다(Spring 빈이 아니다).
 */
@Slf4j
@RequiredArgsConstructor
public class IntercityCandidateAssembler {

    /** 시외 구간이 나누는 세 수단. 이 순서가 곧 후보 순서다 */
    private static final List<TransitMode> INTERCITY_MODES =
            List.of(TransitMode.TRAIN, TransitMode.EXPRESS_BUS, TransitMode.AIR);
    private static final DateTimeFormatter HHMM = DateTimeFormatter.ofPattern("HH:mm");

    private final PlaceQueryService placeQueryService;
    private final TransitScheduleQueryService transitScheduleQueryService;
    private final TransitRouteLookup routeLookup;

    /**
     * 시외 구간. 세 수단을 각각 만든다 — 서울→부산에서 기차 59,800원 2시간 37분과
     * 고속버스 39,700원 4시간은 사용자가 실제로 저울질하는 대안이라 하나로 뭉치면 그 선택이 사라진다.
     */
    TransitCandidateResDTO.Segment intercitySegment(
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
                .defaultMode(multiPref ? null : TransitCandidateServiceImpl.defaultModeOf(candidates))
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
    TransitCandidateResDTO.Segment intercitySegmentWithoutTimetable(
            Pair pair, RoadResult road, String reason, boolean multiPref) {
        Map<TransitMode, IntercityLegs> legsByMode = IntercityLegs.pick(road.paths());
        List<Candidate> candidates = new ArrayList<>();
        for (TransitMode mode : INTERCITY_MODES) {
            Candidate candidate = noTimetableCandidate(
                    mode, legsByMode.get(mode), road.firstStartStation(), road.lastEndStation());
            // ODsay가 이 수단의 경로를 주지 않으면 후보 자체가 없다 — null을 걸러낸다
            if (candidate != null) {
                candidates.add(candidate);
            }
        }
        candidates.addAll(road.roadCandidates());

        return TransitCandidateResDTO.Segment.builder()
                .fromBlockId(pair.from().getId())
                .toBlockId(pair.to().getId())
                .intercity(true)
                .timetableApplied(false)
                .timetableSkipReason(reason)
                .defaultMode(multiPref ? null : TransitCandidateServiceImpl.defaultModeOf(candidates))
                .candidates(candidates)
                .build();
    }

    /**
     * 시간표를 적용하지 않는 시외 후보 하나.
     *
     * <p>{@code legs}가 있으면 그 경로 자신의 시각·leg을 채운다 — 시간표를 못 붙인 것이
     * "아무것도 모른다"는 뜻은 아니다. 접근·이탈은 채우지 않는다: 그건 시간표가 붙어야 기준
     * 시각을 만들 수 있는 door-to-door({@link #doorToDoorCandidate})의 몫이다.
     *
     * <p>{@code legs}가 없으면(ODsay 응답에 이 수단의 경로 자체가 없음) {@code null}이다 —
     * 호출자가 걸러낸다.
     */
    private Candidate noTimetableCandidate(TransitMode mode, IntercityLegs legs, String from, String to) {
        if (legs == null) {
            return null;
        }
        return Candidate.builder()
                .mode(mode)
                .label(IntercityLabel.of(legs.legs(), mode))
                .status(CandidateStatus.OK)
                .durationMin(legs.path().info().totalTime())
                .fareConfidence(TransitResDTO.FareConfidence.UNKNOWN)
                .transferCount(legs.legs().size() - 1)
                .legs(TransitLegResDTO.fromSubPaths(legs.legs()))
                .departures(List.of())
                .build();
    }

    /**
     * 세 수단의 시간표를 가상 스레드로 동시에 부른다({@link ParallelInvoker}).
     *
     * <p>세 수단 모두 subPath가 준 역 ID를 이름 검색 없이 그대로 시간표 API에 넘기므로
     * 수단마다 시간표 조회 1회뿐이다(실측 89/89 성공 — {@link #timetableCandidateFor} 참고).
     * 직렬로 두면 한 요청이 read-timeout 15초 × 3회를 서블릿 스레드에서 그대로 뒤집어쓴다.
     *
     * <p>상한은 호출한 쪽이 넘겨준 {@code budget}이다 — 여러 시외 구간을 담은 요청이면 이
     * 메서드가 구간마다 다시 불리는데, 예산은 요청 전체가 공유하므로 두 번째 이후 구간은 남은
     * 시간만큼만 받는다(항상 0보다 크다 — 호출자가 0이면 아예 부르지 않고 예산 소진 사유로 건너뛴다).
     *
     * <p>순서는 {@link #INTERCITY_MODES} 그대로다 — 후보 순서가 곧 화면 순서다.
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
        try {
            return ParallelInvoker.invokeAllWithin(
                    tasks, budget, i -> Candidate.lookupFailed(INTERCITY_MODES.get(i)), "시외 시간표 조회");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("시외 시간표 조회 인터럽트: from={}, to={}", from, to);
            return INTERCITY_MODES.stream().map(Candidate::lookupFailed).toList();
        }
    }

    /**
     * 수단별 시외 후보. 어느 쪽으로든 이름 검색으로 대체하지 않는다.
     *
     * <p>{@code legs}가 없으면(=ODsay 응답에 <b>이 수단의 경로가 아예 없음</b>) 후보를 만들지
     * 않고 {@code null}이다 — 조회가 실패한 것이 아니라 그런 경로가 없다. 반대로 첫 leg의
     * <b>역 ID 대역을 판별하지 못한</b> 경우는 {@code LOOKUP_FAILED}다 — 그건 경로가 없는 것이
     * 아니라 우리가 판별하지 못한 것이고, 그 구분을 뭉개면 실제 장애가 "여기엔 그 수단이 없다"로 위장된다.
     *
     * <p>접근·이탈 경로({@link #accessLegsOf})부터 구한다 — 첫 leg의 기준 시각 자체가
     * 접근 소요를 반영해야 하기 때문이다({@link #referenceFor}). 접근 경로가 없으면 시간표를
     * 조회해 보지도 않고 {@code null}을 반환한다 — 그 수단 후보를 만들지 않는다(접근시간을 0으로
     * 추측하지 않는다). 호출자({@link #intercityCandidates})가 이 {@code null}을 걸러낸다.
     *
     * <p>{@code mode}(대표 수단)는 후보의 <b>정체</b>일 뿐이다 — {@link IntercityLegs}가 목적지에
     * 도달하는 마지막 leg에서 뽑으므로 {@code pathType 20}에서는 첫 leg의 수단과 다르다. 그래서
     * 첫 leg의 시간표·탑승 여유는 대표 수단이 아니라 <b>그 leg 자신의 대역</b>으로 판별한
     * {@code boardingMode}를 쓴다.
     */
    private Candidate candidateFor(
            TransitMode mode, IntercityLegs legs, Pair pair, int base, LocalDate startDate, int dayNo,
            String from, String to) {
        if (legs == null) {
            return null;
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
     * 블록 → 승차 지점, 하차 지점 → 블록. 시외 경로엔 이 leg가 없어(실측 537건 전부 0) 따로 부른다.
     *
     * <p>접근·이탈 중 하나라도 <b>구할 수 없으면</b> {@code empty}다 — 0분으로 추측하지 않는다.
     * 호출자가 이 empty를 "그 수단 후보를 만들지 않는다"로 읽고 {@code log.warn}으로 남긴다.
     *
     * <p>단 ODsay가 <b>700m 이내</b>라고 답한 경우는 구할 수 없는 것이 아니라 <b>걸어가는 것</b>이다
     * ({@code OdsayTooCloseException}) — 카카오 도보 길찾기로 대신 채운다. 이 둘을 뭉갰다가
     * 서울→속초에서 고속버스 후보가 통째로 사라졌다.
     *
     * <p>package-private인 이유는 {@code TransitCandidateServiceImplTest}가
     * {@code TransitCandidateServiceImpl.accessLegsOf} 위임을 통해 직접 부르기 때문이다.
     */
    Optional<AccessLegs> accessLegsOf(Pair pair, IntercityLegs legs) {
        IntercityLegs.Point boarding = legs.boardingPoint();
        IntercityLegs.Point alighting = legs.alightingPoint();

        Optional<SideLeg> access = sideLegOf(new Leg(
                pair.from().getLat().doubleValue(), pair.from().getLng().doubleValue(),
                boarding.y(), boarding.x()));
        if (access.isEmpty()) {
            log.warn("접근 경로 없음: fromBlockId={}, boarding={}", pair.from().getId(), boarding);
            return Optional.empty();
        }
        Optional<SideLeg> egress = sideLegOf(new Leg(
                alighting.y(), alighting.x(),
                pair.to().getLat().doubleValue(), pair.to().getLng().doubleValue()));
        if (egress.isEmpty()) {
            log.warn("이탈 경로 없음: alighting={}, toBlockId={}", alighting, pair.to().getId());
            return Optional.empty();
        }

        return Optional.of(new AccessLegs(
                access.get().legs(), access.get().durationMin(), access.get().fare(),
                egress.get().legs(), egress.get().durationMin(), egress.get().fare()));
    }

    /**
     * 접근·이탈 한 쪽. 대중교통 경로가 있으면 그것, ODsay가 700m 이내라고 답하면 도보다.
     *
     * <p>도보 소요는 카카오 도보 길찾기의 실측값이다 — 700m를 걷는 시간을 0분으로 두면
     * 그만큼 이른 편을 탈 수 있다고 내밀게 되고, 거리로 지어내면 그건 추정이다. 도보 조회까지
     * 실패하면 {@code empty}다 — 그때는 정말 모른다.
     */
    private Optional<SideLeg> sideLegOf(Leg leg) {
        TransitRouteLookup.RouteLookup lookup =
                routeLookup.of(leg.fromLat(), leg.fromLng(), leg.toLat(), leg.toLng());
        if (!lookup.paths().isEmpty()) {
            OdsayRouteResponse.Path path = lookup.paths().get(0);
            return Optional.of(new SideLeg(
                    TransitLegResDTO.fromSubPaths(path.subPath()),
                    path.info().totalTime(),
                    path.info().payment()));
        }
        if (!lookup.tooClose()) {
            return Optional.empty();
        }
        return placeQueryService
                .getWalkingRoute(leg.fromLat(), leg.fromLng(), leg.toLat(), leg.toLng())
                .map(walk -> new SideLeg(
                        List.of(TransitLegResDTO.Leg.builder()
                                .type(TransitLegResDTO.LegType.WALK)
                                .durationMin(walk.durationMin())
                                .build()),
                        walk.durationMin(),
                        0));
    }

    /** 접근 또는 이탈 한 쪽의 구간·소요·요금. 도보로 채워졌으면 요금은 0이다 */
    private record SideLeg(List<TransitLegResDTO.Leg> legs, int durationMin, Integer fare) {
    }

    /**
     * 접근·대기·시외(환승 포함)·이탈을 door-to-door로 합친 최종 후보.
     *
     * <p>{@code legCandidate}는 시간표가 적용된 leg 후보({@link #timetableCandidateFor} 또는
     * 환승이면 {@link #withConnections}의 결과)다. 여기서는 그 편에 {@code waitMin}을 채우고,
     * top-level {@code durationMin}·{@code fare}를 접근·이탈까지 반영한 door-to-door 값으로 다시 계산한다.
     *
     * <p>편이 하나도 없으면(막차 지남) {@code status=NO_SERVICE}다 — 접근 경로 조회와 시간표
     * 조회 자체는 성공했으니 {@code LOOKUP_FAILED}가 아니다.
     *
     * @param mode         후보의 정체가 되는 대표 수단(목적지에 도달하는 수단). 버킷·아이콘 키다
     * @param boardingMode 실제로 탑승하는 첫 leg의 수단. {@code referenceAt}은 이 수단의 탑승
     *                     여유로 계산한다 — 대표 수단의 여유를 쓰면 기차+항공 경로에서 화면 기준
     *                     시각과 실제 기준이 어긋난다
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
                // 표시 이름은 leg 순서대로 이어 붙인다("시외버스+항공") — 대표 수단만으로 이름을
                // 붙이면 앞 구간이 화면에서 사라진다({@link IntercityLabel})
                .label(IntercityLabel.of(legs.legs(), mode))
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

    /** ODsay는 심야편을 "24:10"으로 준다 — {@link OdsayClock}이 그 표기를 읽는다 */
    private int minutesOf(String hhmm) {
        return OdsayClock.minutesOf(hhmm);
    }

    /**
     * 환승 경로의 두 번째 leg 시간표를 <b>한 번만</b> 불러 첫 leg의 편마다 연결편을 붙인다.
     *
     * <p>두 번째 leg의 수단은 첫 leg과 같다고 가정하지 않고 다시 판별한다({@link #modeOfLeg}) —
     * {@code pathType 20}처럼 두 leg가 서로 다른 수단(기차→항공 등)일 수 있어서다. 판별할 수
     * 없거나 두 번째 leg 조회 자체가 실패하면 추측하지 않고 조회 실패로 낸다.
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
     * 두 leg를 다 아는 이 조립기가 두 번째 leg의 {@code SubPath}에서 이름을 가져와 채운다.
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
     * 고속버스·시외버스는 {@code trafficType}이 달라도 같은 엔드포인트를 쓴다 — {@code mode}가 이미
     * {@link StationIdBands}로 둘을 하나(EXPRESS_BUS)로 합쳐 판별해 뒀으므로 여기서 다시 나누지 않는다.
     *
     * <p>환승 경로(leg 2개)에서는 {@link #withConnections}가 이 메서드를 첫 leg·두 번째 leg에 각각 한
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
        // 버스 시간표엔 편명·편번호가 없어, name 에 출발 시각을 붙여 편마다 유니크하게 만든다.
        return TransitCandidateResDTO.Departure.builder()
                .name("고속버스 " + b.departureTime())
                .grade(busGradeOf(b.busClass()))
                .departureAt(b.departureTime())
                .arrivalAt(durationMin == null
                        ? null
                        : OdsayClock.format(OdsayClock.minutesOf(b.departureTime()) + durationMin))
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
     * <p>비교를 {@code LocalTime}이 아니라 자정 기준 분으로 하는 이유는 ODsay가 심야편을
     * {@code "24:10"}으로 주기 때문이다({@link OdsayClock}). 24시 넘김 편은 1440 이상이라 기준
     * 시각보다 항상 뒤에 오고, 정렬도 자연히 맨 뒤다.
     */
    private List<TransitCandidateResDTO.Departure> afterReference(
            List<TransitCandidateResDTO.Departure> all, LocalTime reference) {
        int referenceMinutes = reference.getHour() * 60 + reference.getMinute();
        return all.stream()
                .filter(d -> OdsayClock.minutesOf(d.departureAt()) >= referenceMinutes)
                .sorted(Comparator.comparingInt(d -> OdsayClock.minutesOf(d.departureAt())))
                .toList();
    }

    /**
     * 후보의 대표값은 첫 출발편에서 가져온다.
     *
     * <p>편이 없어도 {@code status=OK}다 — 조회는 성공했고 "그 시각 이후에는 없다"가
     * 유효한 답이다. {@code status=LOOKUP_FAILED}는 조회 실패에만 쓴다(기존 규칙).
     *
     * @param transferCount 환승 횟수. 직통은 0, 환승 경로는 {@code legs.size() - 1}이다
     *                      ({@link #withConnections}).
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

    /** 자정을 넘는 편(23:30 출발 05:10 도착)도, 24시 넘김 표기("24:10")도 {@link OdsayClock}이 다룬다 */
    private int minutesBetween(String departureAt, String arrivalAt) {
        return OdsayClock.minutesBetween(departureAt, arrivalAt);
    }

    /**
     * 수단별 출발 기준의 날짜와 시각. {@code base}(from 블록의 저장된 종료 시각, 자정 기준 분)에
     * 수단별 탑승 여유({@link BoardingMargin})를 더한 값 하나(절대 분)에서 날짜·시각을 함께 뽑는다.
     *
     * <p>날짜와 시각을 각자 다른 값으로 계산하면 안 된다 — from 블록이 23:20에 끝나고 항공
     * 여유가 40분이면 절대 기준은 다음 날 00:00이다. 그래서 이 메서드가 날짜·시각을 항상 같은
     * 절대값에서 함께 만든다.
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
}
