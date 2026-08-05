package com.ssafy.ieumgil.domain.transit.service;

import com.ssafy.ieumgil.domain.block.entity.Block;
import com.ssafy.ieumgil.domain.block.entity.BlockCategory;
import com.ssafy.ieumgil.domain.block.entity.BlockSource;
import com.ssafy.ieumgil.domain.block.repository.BlockRepository;
import com.ssafy.ieumgil.domain.place.dto.PlaceResDTO;
import com.ssafy.ieumgil.domain.place.service.PlaceQueryService;
import com.ssafy.ieumgil.domain.project.entity.Project;
import com.ssafy.ieumgil.domain.project.entity.TransportPref;
import com.ssafy.ieumgil.domain.project.repository.ProjectRepository;
import com.ssafy.ieumgil.domain.transit.dto.OdsayRouteResponse;
import com.ssafy.ieumgil.domain.transit.dto.TransitCandidateResDTO;
import com.ssafy.ieumgil.domain.transit.dto.TransitCandidateResDTO.Candidate;
import com.ssafy.ieumgil.domain.transit.dto.TransitCandidateResDTO.TransitMode;
import com.ssafy.ieumgil.domain.transit.dto.TransitResDTO;
import com.ssafy.ieumgil.domain.transit.dto.TransitScheduleResDTO;
import com.ssafy.ieumgil.domain.transit.exception.TransitErrorCode;
import com.ssafy.ieumgil.domain.transit.exception.TransitException;
import com.ssafy.ieumgil.global.exception.CustomException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class TransitCandidateServiceImplTest {

    @Mock
    private BlockRepository blockRepository;
    @Mock
    private ProjectRepository projectRepository;
    @Mock
    private PlaceQueryService placeQueryService;
    @Mock
    private PublicTransitQueryService publicTransitQueryService;
    @Mock
    private FuelPriceProvider fuelPriceProvider;
    @Mock
    private TransitScheduleQueryService transitScheduleQueryService;
    /**
     * 선정기는 목이 아니라 진짜 구현이다 — 라벨과 5개 선정은 실제 규칙을 봐야 의미가 있다.
     * 그래도 spy로 두는 이유는 "시외 경로가 여기 닿지 않는다"를 호출 여부로 단정하기 위해서다.
     */
    @Spy
    private TransitRouteSelector routeSelector = new TransitRouteSelector();

    @InjectMocks
    private TransitCandidateServiceImpl service;

    /** 서울시청 */
    private static final double LAT_A = 37.5666, LNG_A = 126.9784;
    /** 강남역 — 시청에서 직선 약 8.8km */
    private static final double LAT_B = 37.4979, LNG_B = 127.0276;
    /**
     * 시청에서 정북으로 직선 약 1.2km(위도 0.001도 ≈ 111m).
     *
     * <p>근거리 임계(300m)도 도보 임계(2km)도 아닌 <b>중간 대역</b>이다. 이 대역에서 무엇이 기본이
     * 되는지가 제품 결정이라 좌표를 상수로 못 박는다 — 여기서는 도보가 아니라 프로젝트가 고른
     * 선호 수단이 기본이다. 1.9km 구간을 "도보 30분"으로 기본 제안하면 일괄 계산 결과가 도보로
     * 채워지고 사용자가 생성 때 고른 설정이 무시된다.
     */
    private static final double LAT_MID = LAT_A + 0.0108;
    /**
     * 시청에서 정북으로 하버사인 301.34m — 근거리 임계(300m) 바로 위.
     *
     * <p>임계 판정이 사라져도 먼 구간 테스트는 그대로 통과한다(8.8km는 어느 쪽으로도 대중교통·택시를
     * 부른다). 임계 <b>바로 위아래</b>를 못 박아야 호출을 실제로 걸러내는지가 드러난다.
     */
    private static final double LAT_JUST_OVER_NEAR = LAT_A + 0.00271;
    /** 시청에서 정북으로 하버사인 299.11m — 근거리 임계(300m) 바로 아래. */
    private static final double LAT_JUST_UNDER_NEAR = LAT_A + 0.00269;
    /** 시청에서 정북으로 하버사인 2001.51m — 도보 임계(2km) 바로 위. */
    private static final double LAT_JUST_OVER_WALK = LAT_A + 0.018;
    /**
     * 시청에서 정북으로 하버사인 1999.28m — 도보 임계(2km) 바로 아래.
     *
     * <p>위쪽만 못 박으면 임계를 <b>조이는</b> 회귀를 놓친다 — 2km를 1.5km로 줄여도 1.2km는 여전히
     * 미만이고 2001m는 여전히 초과라 통과해 버린다. 아래쪽 0.72m를 여기서 지킨다.
     */
    private static final double LAT_JUST_UNDER_WALK = LAT_A + 0.01798;

    /** 청주 */
    private static final double LAT_CHEONGJU = 36.6424, LNG_CHEONGJU = 127.4890;
    /** 제주 */
    private static final double LAT_JEJU = 33.4996, LNG_JEJU = 126.5312;
    /** 서울역 */
    private static final double LAT_SEOUL = 37.5546, LNG_SEOUL = 126.9707;
    /** 부산역 — 서울역에서 직선 약 325km */
    private static final double LAT_BUSAN = 35.1151, LNG_BUSAN = 129.0413;

    private static final Long PROJECT_ID = 10L;

    private Block blockAt(long id, double lat, double lng) {
        return blockAt(id, lat, lng, 1);
    }

    private Block blockAt(long id, double lat, double lng, int dayNo) {
        return Block.builder()
                .id(id).dayNo(dayNo).orderKey("a" + id).name("블록" + id)
                .category(BlockCategory.SPOT).durationMin(60).budget(0)
                .lat(BigDecimal.valueOf(lat)).lng(BigDecimal.valueOf(lng))
                .source(BlockSource.KAKAO)
                .build();
    }

    private void givenProject(TransportPref pref) {
        given(projectRepository.findByIdAndDeletedAtIsNull(PROJECT_ID))
                .willReturn(Optional.of(Project.builder().transportPrefs(List.of(pref)).build()));
    }

    /** ODsay 경로 목록 응답 하나를 durationMin·fare·intervalMin·distanceM만으로 단순화해 만든다. */
    private OdsayRouteResponse.Path pathOf(int durationMin, Integer fare, Integer intervalMin, Integer distanceM) {
        return new OdsayRouteResponse.Path(1,
                new OdsayRouteResponse.Info(durationMin, fare, intervalMin, distanceM, null, null, null, null, null),
                List.of());
    }

    @Test
    @DisplayName("PUBLIC 프로젝트의 먼 구간은 대중교통이 기본이다")
    void publicPrefDefaultsToTransit() {
        givenProject(TransportPref.PUBLIC);
        given(blockRepository.findAllByIdInAndProject_IdAndDeletedAtIsNull(List.of(1L, 2L), PROJECT_ID))
                .willReturn(List.of(blockAt(1L, LAT_A, LNG_A), blockAt(2L, LAT_B, LNG_B)));
        given(publicTransitQueryService.getCombinedRoutes(LAT_A, LNG_A, LAT_B, LNG_B))
                .willReturn(List.of(pathOf(25, 1400, 8, null)));
        given(placeQueryService.getTaxiRoute(LAT_A, LNG_A, LAT_B, LNG_B))
                .willReturn(Optional.of(new PlaceResDTO.TaxiRoute(8900, 0, 6800, 12)));

        TransitCandidateResDTO.Result result = service.calculate(PROJECT_ID, List.of(1L, 2L), null);

        TransitCandidateResDTO.Segment segment = result.segments().get(0);
        assertThat(segment.fromBlockId()).isEqualTo(1L);
        assertThat(segment.toBlockId()).isEqualTo(2L);
        assertThat(segment.defaultMode()).isEqualTo(TransitMode.TRANSIT);
        assertThat(segment.candidates()).extracting(TransitCandidateResDTO.Candidate::mode)
                .containsExactly(TransitMode.TRANSIT, TransitMode.TAXI);  // 2km를 훌쩍 넘어 도보는 없다
        // 목에 넣은 durationMin(25)·fare(1400)·intervalMin(8)이 서로 바뀌어도 잡히도록 구별되는 값을 쓴다
        TransitCandidateResDTO.Candidate transit = segment.candidates().get(0);
        assertThat(transit.durationMin()).isEqualTo(25);
        assertThat(transit.fare()).isEqualTo(1400);
        assertThat(transit.intervalMin()).isEqualTo(8);
        assertThat(transit.label()).isEqualTo(TransitMode.TRANSIT.label());
        // 이 목은 distanceM을 stub하지 않아 null일 뿐이다 — 실제 매핑 계약은
        // 대중교통_후보에_경로_실거리가_담긴다()가 검증한다. 여기서 null을 보는 것은 목 설정 때문이지 계약이 아니다
        assertThat(transit.distanceM()).isNull();
    }

    @Test
    @DisplayName("CAR 프로젝트의 먼 구간은 자차가 기본이다 — 대중교통은 후보에도 없다")
    void carPrefDefaultsToCarAndExcludesTransit() {
        givenProject(TransportPref.CAR);
        given(blockRepository.findAllByIdInAndProject_IdAndDeletedAtIsNull(List.of(1L, 2L), PROJECT_ID))
                .willReturn(List.of(blockAt(1L, LAT_A, LNG_A), blockAt(2L, LAT_B, LNG_B)));
        given(placeQueryService.getTaxiRoute(LAT_A, LNG_A, LAT_B, LNG_B))
                .willReturn(Optional.of(new PlaceResDTO.TaxiRoute(8900, 0, 6800, 12)));

        TransitCandidateResDTO.Result result = service.calculate(PROJECT_ID, List.of(1L, 2L), null);

        TransitCandidateResDTO.Segment segment = result.segments().get(0);
        assertThat(segment.defaultMode()).isEqualTo(TransitMode.CAR);
        assertThat(segment.candidates()).extracting(TransitCandidateResDTO.Candidate::mode)
                .containsExactly(TransitMode.CAR, TransitMode.TAXI);
    }

    @Test
    @DisplayName("300m 미만이면 도보가 기본이고 대중교통·택시는 호출조차 하지 않는다")
    void nearSegmentUsesWalkOnly() {
        double nearLat = LAT_A + 0.00135;  // 위도 0.00135도 ≈ 150m
        givenProject(TransportPref.PUBLIC);
        given(blockRepository.findAllByIdInAndProject_IdAndDeletedAtIsNull(List.of(1L, 2L), PROJECT_ID))
                .willReturn(List.of(blockAt(1L, LAT_A, LNG_A), blockAt(2L, nearLat, LNG_A)));
        given(placeQueryService.getWalkingRoute(LAT_A, LNG_A, nearLat, LNG_A))
                .willReturn(Optional.of(new PlaceResDTO.WalkingRoute(170, 3)));

        TransitCandidateResDTO.Result result = service.calculate(PROJECT_ID, List.of(1L, 2L), null);

        TransitCandidateResDTO.Segment segment = result.segments().get(0);
        assertThat(segment.defaultMode()).isEqualTo(TransitMode.WALK);
        assertThat(segment.candidates()).extracting(TransitCandidateResDTO.Candidate::mode)
                .containsExactly(TransitMode.WALK);
        // 300m 미만은 대중교통 경로를 아예 묻지 않는다 — 이 verify가 그 호출 절약의 증거다
        verify(publicTransitQueryService, never())
                .getCombinedRoutes(anyDouble(), anyDouble(), anyDouble(), anyDouble());
        verify(placeQueryService, never())
                .getTaxiRoute(anyDouble(), anyDouble(), anyDouble(), anyDouble());
    }

    @Test
    @DisplayName("2km를 넘으면 도보는 후보에서 빠진다 — available=false가 아니라 아예 없다")
    void farSegmentHasNoWalkCandidate() {
        givenProject(TransportPref.PUBLIC);
        given(blockRepository.findAllByIdInAndProject_IdAndDeletedAtIsNull(List.of(1L, 2L), PROJECT_ID))
                .willReturn(List.of(blockAt(1L, LAT_A, LNG_A), blockAt(2L, LAT_B, LNG_B)));
        given(publicTransitQueryService.getCombinedRoutes(LAT_A, LNG_A, LAT_B, LNG_B))
                .willReturn(List.of(pathOf(25, 1400, 8, null)));
        given(placeQueryService.getTaxiRoute(LAT_A, LNG_A, LAT_B, LNG_B))
                .willReturn(Optional.of(new PlaceResDTO.TaxiRoute(8900, 0, 6800, 12)));

        TransitCandidateResDTO.Result result = service.calculate(PROJECT_ID, List.of(1L, 2L), null);

        assertThat(result.segments().get(0).candidates())
                .extracting(TransitCandidateResDTO.Candidate::mode)
                .doesNotContain(TransitMode.WALK);
        verify(placeQueryService, never())
                .getWalkingRoute(anyDouble(), anyDouble(), anyDouble(), anyDouble());
    }

    @Test
    @DisplayName("기본 수단 조회가 실패하면 살아있는 다음 후보로 기본을 옮긴다")
    void fallsBackToNextAvailableCandidate() {
        givenProject(TransportPref.PUBLIC);
        given(blockRepository.findAllByIdInAndProject_IdAndDeletedAtIsNull(List.of(1L, 2L), PROJECT_ID))
                .willReturn(List.of(blockAt(1L, LAT_A, LNG_A), blockAt(2L, LAT_B, LNG_B)));
        given(publicTransitQueryService.getCombinedRoutes(LAT_A, LNG_A, LAT_B, LNG_B))
                .willThrow(new TransitException(TransitErrorCode.ROUTE_NOT_FOUND));
        given(placeQueryService.getTaxiRoute(LAT_A, LNG_A, LAT_B, LNG_B))
                .willReturn(Optional.of(new PlaceResDTO.TaxiRoute(8900, 0, 6800, 12)));

        TransitCandidateResDTO.Result result = service.calculate(PROJECT_ID, List.of(1L, 2L), null);

        TransitCandidateResDTO.Segment segment = result.segments().get(0);
        assertThat(segment.defaultMode()).isEqualTo(TransitMode.TAXI);
        assertThat(segment.candidates())
                .extracting(TransitCandidateResDTO.Candidate::mode, TransitCandidateResDTO.Candidate::available)
                .containsExactly(tuple(TransitMode.TRANSIT, false), tuple(TransitMode.TAXI, true));
    }

    @Test
    @DisplayName("모든 조회가 실패하면 defaultMode가 null이다")
    void allFailuresYieldNullDefault() {
        givenProject(TransportPref.PUBLIC);
        given(blockRepository.findAllByIdInAndProject_IdAndDeletedAtIsNull(List.of(1L, 2L), PROJECT_ID))
                .willReturn(List.of(blockAt(1L, LAT_A, LNG_A), blockAt(2L, LAT_B, LNG_B)));
        given(publicTransitQueryService.getCombinedRoutes(LAT_A, LNG_A, LAT_B, LNG_B))
                .willThrow(new TransitException(TransitErrorCode.ROUTE_NOT_FOUND));
        given(placeQueryService.getTaxiRoute(LAT_A, LNG_A, LAT_B, LNG_B))
                .willReturn(Optional.empty());

        TransitCandidateResDTO.Result result = service.calculate(PROJECT_ID, List.of(1L, 2L), null);

        TransitCandidateResDTO.Segment segment = result.segments().get(0);
        assertThat(segment.defaultMode()).isNull();
        assertThat(segment.candidates()).isNotEmpty()
                .allSatisfy(candidate -> assertThat(candidate.available()).isFalse());
    }

    @Test
    @DisplayName("같은 좌표쌍이 두 번 나오면 외부 호출은 한 번만 한다")
    void deduplicatesIdenticalSegments() {
        givenProject(TransportPref.PUBLIC);
        given(blockRepository.findAllByIdInAndProject_IdAndDeletedAtIsNull(List.of(1L, 2L, 1L, 2L), PROJECT_ID))
                .willReturn(List.of(blockAt(1L, LAT_A, LNG_A), blockAt(2L, LAT_B, LNG_B)));
        // A→B가 두 번, B→A가 한 번 나온다. 방향이 다르면 다른 구간이므로 각각 한 번씩만 불려야 한다.
        given(publicTransitQueryService.getCombinedRoutes(LAT_A, LNG_A, LAT_B, LNG_B))
                .willReturn(List.of(pathOf(25, 1400, 8, null)));
        given(publicTransitQueryService.getCombinedRoutes(LAT_B, LNG_B, LAT_A, LNG_A))
                .willReturn(List.of(pathOf(27, 1400, 8, null)));
        given(placeQueryService.getTaxiRoute(LAT_A, LNG_A, LAT_B, LNG_B))
                .willReturn(Optional.of(new PlaceResDTO.TaxiRoute(8900, 0, 6800, 12)));
        given(placeQueryService.getTaxiRoute(LAT_B, LNG_B, LAT_A, LNG_A))
                .willReturn(Optional.of(new PlaceResDTO.TaxiRoute(9100, 0, 6800, 13)));

        TransitCandidateResDTO.Result result = service.calculate(PROJECT_ID, List.of(1L, 2L, 1L, 2L), null);

        assertThat(result.segments()).hasSize(3);
        verify(publicTransitQueryService, times(1)).getCombinedRoutes(LAT_A, LNG_A, LAT_B, LNG_B);
        verify(placeQueryService, times(1)).getTaxiRoute(LAT_A, LNG_A, LAT_B, LNG_B);
    }

    @Test
    @DisplayName("자차 요금은 통행료에 연료비를 더한 추정치다")
    void carFareIsTollPlusEstimatedFuel() {
        givenProject(TransportPref.CAR);
        given(blockRepository.findAllByIdInAndProject_IdAndDeletedAtIsNull(List.of(1L, 2L), PROJECT_ID))
                .willReturn(List.of(blockAt(1L, LAT_A, LNG_A), blockAt(2L, LAT_B, LNG_B)));
        given(placeQueryService.getTaxiRoute(LAT_A, LNG_A, LAT_B, LNG_B))
                .willReturn(Optional.of(new PlaceResDTO.TaxiRoute(8900, 2400, 8300, 22)));
        given(fuelPriceProvider.pricePerLiter()).willReturn(1700);

        TransitCandidateResDTO.Result result = service.calculate(PROJECT_ID, List.of(1L, 2L), null);

        TransitCandidateResDTO.Candidate car = result.segments().get(0).candidates().get(0);
        assertThat(car.mode()).isEqualTo(TransitMode.CAR);
        // 실경로 8.3km ÷ 연비 12km/L × 1700원/L
        assertThat(car.fare()).isEqualTo(2400 + Math.round(8.3 / 12.0 * 1700));
        assertThat(car.fareConfidence()).isEqualTo(TransitResDTO.FareConfidence.ESTIMATE);
        // 택시 요금은 자차 요금과 무관하게 카카오가 준 값 그대로다
        assertThat(result.segments().get(0).candidates().get(1).fare()).isEqualTo(8900);
    }

    @Test
    @DisplayName("남의 프로젝트 블록 id가 섞이면 거절한다")
    void rejectsBlockIdsFromAnotherProject() {
        givenProject(TransportPref.PUBLIC);
        // 레포가 프로젝트 조건으로 걸러내므로, 남의 블록은 조회 결과에서 빠진 채로 돌아온다.
        given(blockRepository.findAllByIdInAndProject_IdAndDeletedAtIsNull(List.of(1L, 2L), PROJECT_ID))
                .willReturn(List.of(blockAt(1L, LAT_A, LNG_A)));

        assertThatThrownBy(() -> service.calculate(PROJECT_ID, List.of(1L, 2L), null))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("code", TransitErrorCode.INVALID_BLOCKS);
    }

    @Test
    @DisplayName("좌표가 없는 블록이 섞이면 거절한다")
    void rejectsBlocksWithoutCoordinates() {
        givenProject(TransportPref.PUBLIC);
        // 장소성 없는 블록(ETC 등)은 좌표가 없다. 구간의 끝점이 될 수 없으므로 계산 자체를 막는다.
        Block noCoordinates = Block.builder()
                .id(2L).dayNo(1).orderKey("a2").name("자유시간")
                .category(BlockCategory.ETC).durationMin(60).budget(0)
                .source(BlockSource.MANUAL)
                .build();
        given(blockRepository.findAllByIdInAndProject_IdAndDeletedAtIsNull(List.of(1L, 2L), PROJECT_ID))
                .willReturn(List.of(blockAt(1L, LAT_A, LNG_A), noCoordinates));

        assertThatThrownBy(() -> service.calculate(PROJECT_ID, List.of(1L, 2L), null))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("code", TransitErrorCode.COORDINATE_REQUIRED);
    }

    @Test
    @DisplayName("블록이 하나뿐이면 구간이 없다")
    void singleBlockYieldsNoSegments() {
        givenProject(TransportPref.PUBLIC);
        given(blockRepository.findAllByIdInAndProject_IdAndDeletedAtIsNull(List.of(1L), PROJECT_ID))
                .willReturn(List.of(blockAt(1L, LAT_A, LNG_A)));

        TransitCandidateResDTO.Result result = service.calculate(PROJECT_ID, List.of(1L), null);

        assertThat(result.segments()).isEmpty();
        verifyNoInteractions(publicTransitQueryService);
        verifyNoInteractions(placeQueryService);
    }

    @Test
    @DisplayName("대중교통 후보의 distanceM은 ODsay 경로 실거리로 채운다")
    void 대중교통_후보에_경로_실거리가_담긴다() {
        givenProject(TransportPref.PUBLIC);
        given(blockRepository.findAllByIdInAndProject_IdAndDeletedAtIsNull(List.of(1L, 2L), PROJECT_ID))
                .willReturn(List.of(blockAt(1L, LAT_A, LNG_A), blockAt(2L, LAT_B, LNG_B)));
        given(publicTransitQueryService.getCombinedRoutes(LAT_A, LNG_A, LAT_B, LNG_B))
                .willReturn(List.of(pathOf(44, 1500, 9, 12841)));

        TransitCandidateResDTO.Result result = service.calculate(PROJECT_ID, List.of(1L, 2L), null);

        TransitCandidateResDTO.Candidate transit = result.segments().get(0).candidates().stream()
                .filter(c -> c.mode() == TransitMode.TRANSIT).findFirst().orElseThrow();
        assertThat(transit.distanceM()).isEqualTo(12841);
    }

    @Test
    @DisplayName("300m~2km 중간 대역의 PUBLIC 프로젝트는 도보가 아니라 대중교통이 기본이다")
    void midRangeSegmentKeepsPreferredModeAsDefaultForPublic() {
        givenProject(TransportPref.PUBLIC);
        given(blockRepository.findAllByIdInAndProject_IdAndDeletedAtIsNull(List.of(1L, 2L), PROJECT_ID))
                .willReturn(List.of(blockAt(1L, LAT_A, LNG_A), blockAt(2L, LAT_MID, LNG_A)));
        given(publicTransitQueryService.getCombinedRoutes(LAT_A, LNG_A, LAT_MID, LNG_A))
                .willReturn(List.of(pathOf(9, 1400, 6, null)));
        given(placeQueryService.getTaxiRoute(LAT_A, LNG_A, LAT_MID, LNG_A))
                .willReturn(Optional.of(new PlaceResDTO.TaxiRoute(4800, 0, 1500, 6)));
        given(placeQueryService.getWalkingRoute(LAT_A, LNG_A, LAT_MID, LNG_A))
                .willReturn(Optional.of(new PlaceResDTO.WalkingRoute(1400, 21)));

        TransitCandidateResDTO.Result result = service.calculate(PROJECT_ID, List.of(1L, 2L), null);

        TransitCandidateResDTO.Segment segment = result.segments().get(0);
        // 걸을 수는 있는 거리지만(도보 후보가 목록에 있다) 기본은 선호 수단이다.
        assertThat(segment.defaultMode()).isEqualTo(TransitMode.TRANSIT);
        assertThat(segment.candidates()).extracting(TransitCandidateResDTO.Candidate::mode)
                .containsExactly(TransitMode.TRANSIT, TransitMode.TAXI, TransitMode.WALK);
        // durationMin(21)·distanceM(1400)이 서로 바뀌어도 잡히도록 WALK 후보 값을 단정한다
        assertThat(segment.candidates().get(2))
                .extracting(TransitCandidateResDTO.Candidate::durationMin, TransitCandidateResDTO.Candidate::distanceM)
                .containsExactly(21, 1400);
        // 2km 미만이므로 도보도 실제로 조회한다 — 세 수단 전부 부른다.
        verify(publicTransitQueryService).getCombinedRoutes(LAT_A, LNG_A, LAT_MID, LNG_A);
        verify(placeQueryService).getTaxiRoute(LAT_A, LNG_A, LAT_MID, LNG_A);
        verify(placeQueryService).getWalkingRoute(LAT_A, LNG_A, LAT_MID, LNG_A);
    }

    @Test
    @DisplayName("300m~2km 중간 대역의 CAR 프로젝트는 자차가 기본이고 도보는 후보로만 남는다")
    void midRangeSegmentKeepsPreferredModeAsDefaultForCar() {
        givenProject(TransportPref.CAR);
        given(blockRepository.findAllByIdInAndProject_IdAndDeletedAtIsNull(List.of(1L, 2L), PROJECT_ID))
                .willReturn(List.of(blockAt(1L, LAT_A, LNG_A), blockAt(2L, LAT_MID, LNG_A)));
        given(placeQueryService.getTaxiRoute(LAT_A, LNG_A, LAT_MID, LNG_A))
                .willReturn(Optional.of(new PlaceResDTO.TaxiRoute(4800, 0, 1500, 6)));
        given(placeQueryService.getWalkingRoute(LAT_A, LNG_A, LAT_MID, LNG_A))
                .willReturn(Optional.of(new PlaceResDTO.WalkingRoute(1400, 21)));
        given(fuelPriceProvider.pricePerLiter()).willReturn(1869);

        TransitCandidateResDTO.Result result = service.calculate(PROJECT_ID, List.of(1L, 2L), null);

        TransitCandidateResDTO.Segment segment = result.segments().get(0);
        assertThat(segment.defaultMode()).isEqualTo(TransitMode.CAR);
        assertThat(segment.candidates()).extracting(TransitCandidateResDTO.Candidate::mode)
                .containsExactly(TransitMode.CAR, TransitMode.TAXI, TransitMode.WALK);
    }

    @Test
    @DisplayName("근거리 임계 바로 위(301m)는 대중교통·택시까지 실제로 조회한다")
    void justOverNearThresholdCallsAllModes() {
        givenProject(TransportPref.PUBLIC);
        given(blockRepository.findAllByIdInAndProject_IdAndDeletedAtIsNull(List.of(1L, 2L), PROJECT_ID))
                .willReturn(List.of(blockAt(1L, LAT_A, LNG_A), blockAt(2L, LAT_JUST_OVER_NEAR, LNG_A)));
        given(publicTransitQueryService.getCombinedRoutes(LAT_A, LNG_A, LAT_JUST_OVER_NEAR, LNG_A))
                .willReturn(List.of(pathOf(4, 1400, 6, null)));
        given(placeQueryService.getTaxiRoute(LAT_A, LNG_A, LAT_JUST_OVER_NEAR, LNG_A))
                .willReturn(Optional.of(new PlaceResDTO.TaxiRoute(4800, 0, 380, 2)));
        given(placeQueryService.getWalkingRoute(LAT_A, LNG_A, LAT_JUST_OVER_NEAR, LNG_A))
                .willReturn(Optional.of(new PlaceResDTO.WalkingRoute(360, 6)));

        TransitCandidateResDTO.Result result = service.calculate(PROJECT_ID, List.of(1L, 2L), null);

        assertThat(result.segments().get(0).candidates()).extracting(TransitCandidateResDTO.Candidate::mode)
                .containsExactly(TransitMode.TRANSIT, TransitMode.TAXI, TransitMode.WALK);
        verify(publicTransitQueryService).getCombinedRoutes(LAT_A, LNG_A, LAT_JUST_OVER_NEAR, LNG_A);
        verify(placeQueryService).getTaxiRoute(LAT_A, LNG_A, LAT_JUST_OVER_NEAR, LNG_A);
        verify(placeQueryService).getWalkingRoute(LAT_A, LNG_A, LAT_JUST_OVER_NEAR, LNG_A);
    }

    @Test
    @DisplayName("근거리 임계 바로 아래(299m)는 대중교통·택시를 호출하지 않는다")
    void justUnderNearThresholdSkipsOtherModes() {
        givenProject(TransportPref.PUBLIC);
        given(blockRepository.findAllByIdInAndProject_IdAndDeletedAtIsNull(List.of(1L, 2L), PROJECT_ID))
                .willReturn(List.of(blockAt(1L, LAT_A, LNG_A), blockAt(2L, LAT_JUST_UNDER_NEAR, LNG_A)));
        given(placeQueryService.getWalkingRoute(LAT_A, LNG_A, LAT_JUST_UNDER_NEAR, LNG_A))
                .willReturn(Optional.of(new PlaceResDTO.WalkingRoute(330, 5)));

        TransitCandidateResDTO.Result result = service.calculate(PROJECT_ID, List.of(1L, 2L), null);

        assertThat(result.segments().get(0).candidates()).extracting(TransitCandidateResDTO.Candidate::mode)
                .containsExactly(TransitMode.WALK);
        // 300m 미만은 대중교통 경로를 아예 묻지 않는다 — 이 verify가 그 호출 절약의 증거다
        verify(publicTransitQueryService, never())
                .getCombinedRoutes(anyDouble(), anyDouble(), anyDouble(), anyDouble());
        verify(placeQueryService, never())
                .getTaxiRoute(anyDouble(), anyDouble(), anyDouble(), anyDouble());
    }

    @Test
    @DisplayName("도보 임계 바로 위(2001m)는 도보를 호출하지 않는다")
    void overWalkThresholdSkipsWalkCall() {
        givenProject(TransportPref.PUBLIC);
        given(blockRepository.findAllByIdInAndProject_IdAndDeletedAtIsNull(List.of(1L, 2L), PROJECT_ID))
                .willReturn(List.of(blockAt(1L, LAT_A, LNG_A), blockAt(2L, LAT_JUST_OVER_WALK, LNG_A)));
        given(publicTransitQueryService.getCombinedRoutes(LAT_A, LNG_A, LAT_JUST_OVER_WALK, LNG_A))
                .willReturn(List.of(pathOf(12, 1400, 7, null)));
        given(placeQueryService.getTaxiRoute(LAT_A, LNG_A, LAT_JUST_OVER_WALK, LNG_A))
                .willReturn(Optional.of(new PlaceResDTO.TaxiRoute(5800, 0, 2600, 9)));

        TransitCandidateResDTO.Result result = service.calculate(PROJECT_ID, List.of(1L, 2L), null);

        assertThat(result.segments().get(0).candidates()).extracting(TransitCandidateResDTO.Candidate::mode)
                .containsExactly(TransitMode.TRANSIT, TransitMode.TAXI);
        verify(placeQueryService, never())
                .getWalkingRoute(anyDouble(), anyDouble(), anyDouble(), anyDouble());
    }

    @Test
    @DisplayName("도보 임계 바로 아래(1999m)는 도보를 후보에 넣고 실제로 조회한다")
    void justUnderWalkThresholdKeepsWalkCandidate() {
        givenProject(TransportPref.PUBLIC);
        given(blockRepository.findAllByIdInAndProject_IdAndDeletedAtIsNull(List.of(1L, 2L), PROJECT_ID))
                .willReturn(List.of(blockAt(1L, LAT_A, LNG_A), blockAt(2L, LAT_JUST_UNDER_WALK, LNG_A)));
        given(publicTransitQueryService.getCombinedRoutes(LAT_A, LNG_A, LAT_JUST_UNDER_WALK, LNG_A))
                .willReturn(List.of(pathOf(12, 1400, 7, null)));
        given(placeQueryService.getTaxiRoute(LAT_A, LNG_A, LAT_JUST_UNDER_WALK, LNG_A))
                .willReturn(Optional.of(new PlaceResDTO.TaxiRoute(5800, 0, 2600, 9)));
        given(placeQueryService.getWalkingRoute(LAT_A, LNG_A, LAT_JUST_UNDER_WALK, LNG_A))
                .willReturn(Optional.of(new PlaceResDTO.WalkingRoute(2400, 34)));

        TransitCandidateResDTO.Result result = service.calculate(PROJECT_ID, List.of(1L, 2L), null);

        assertThat(result.segments().get(0).candidates()).extracting(TransitCandidateResDTO.Candidate::mode)
                .containsExactly(TransitMode.TRANSIT, TransitMode.TAXI, TransitMode.WALK);
        verify(placeQueryService).getWalkingRoute(LAT_A, LNG_A, LAT_JUST_UNDER_WALK, LNG_A);
    }

    @Test
    @DisplayName("육로 대안이 하나도 없으면 자차·택시 후보 자체를 만들지 않는다")
    void 육로가_없으면_자차_택시_후보를_만들지_않는다() {
        // 예전에는 이 상황(항공 경로 하나만 있음)에서 자차·택시를 페리 경고 + ESTIMATE로
        // "강등"해서 내보냈다. 그런데 청주-제주처럼 도로 주행만으로 556,600원을 계산해 놓고
        // 경고만 붙이는 건 사용자에게 여전히 오해를 준다 — 이제는 후보 자체를 만들지 않는다.
        givenProject(TransportPref.CAR);
        given(blockRepository.findAllByIdInAndProject_IdAndDeletedAtIsNull(List.of(1L, 3L), PROJECT_ID))
                .willReturn(List.of(cheongjuBlock(), jejuBlock()));
        // ODsay가 준 유일한 경로가 항공이다 = 육로 대안이 없다
        given(publicTransitQueryService.getCombinedRoutes(anyDouble(), anyDouble(), anyDouble(), anyDouble()))
                .willReturn(List.of(airPath()));

        TransitCandidateResDTO.Result result = service.calculate(PROJECT_ID, List.of(1L, 3L), null);

        assertThat(result.segments().get(0).candidates())
                .extracting(TransitCandidateResDTO.Candidate::mode)
                .doesNotContain(TransitMode.CAR, TransitMode.TAXI);
        // 육로가 없다고 이미 확정됐으니 드라이빙 조회 자체를 하지 않는다 — 불필요한 외부 호출 생략
        verify(placeQueryService, never())
                .getTaxiRoute(anyDouble(), anyDouble(), anyDouble(), anyDouble());
    }

    @Test
    @DisplayName("한 경로 안에 육로 구간과 해운 구간이 섞여 있어도 그 경로는 육로로 치지 않는다")
    void 버스와_해운이_섞인_경로는_육로가_아니다() {
        // hasRoadPath는 경로 하나하나를 본다 — 그 경로의 일부(버스)가 육로라고 해서 경로 전체를
        // 육로로 인정하면 안 된다("항구까지는 버스, 그다음은 배"인 경로가 유일한 대안일 때
        // 자차·택시가 여전히 후보로 남으면 안 되기 때문이다).
        givenProject(TransportPref.CAR);
        given(blockRepository.findAllByIdInAndProject_IdAndDeletedAtIsNull(List.of(1L, 3L), PROJECT_ID))
                .willReturn(List.of(cheongjuBlock(), jejuBlock()));
        given(publicTransitQueryService.getCombinedRoutes(anyDouble(), anyDouble(), anyDouble(), anyDouble()))
                .willReturn(List.of(busPlusFerryPath()));

        TransitCandidateResDTO.Result result = service.calculate(PROJECT_ID, List.of(1L, 3L), null);

        assertThat(result.segments().get(0).candidates())
                .extracting(TransitCandidateResDTO.Candidate::mode)
                .doesNotContain(TransitMode.CAR, TransitMode.TAXI);
        verify(placeQueryService, never())
                .getTaxiRoute(anyDouble(), anyDouble(), anyDouble(), anyDouble());
    }

    @Test
    @DisplayName("육로 경로면 자차·택시 후보가 그대로 있고 택시 요금은 CONFIRMED다")
    void 육로_구간은_후보를_그대로_낸다() {
        givenProject(TransportPref.CAR);
        given(blockRepository.findAllByIdInAndProject_IdAndDeletedAtIsNull(List.of(1L, 2L), PROJECT_ID))
                .willReturn(List.of(blockAt(1L, LAT_A, LNG_A), blockAt(2L, LAT_B, LNG_B)));
        given(publicTransitQueryService.getCombinedRoutes(anyDouble(), anyDouble(), anyDouble(), anyDouble()))
                .willReturn(List.of(busPath()));
        given(placeQueryService.getTaxiRoute(anyDouble(), anyDouble(), anyDouble(), anyDouble()))
                .willReturn(Optional.of(new PlaceResDTO.TaxiRoute(14900, 0, 10327, 32)));

        TransitCandidateResDTO.Result result = service.calculate(PROJECT_ID, List.of(1L, 2L), null);

        TransitCandidateResDTO.Candidate taxi = candidateOf(result, TransitMode.TAXI);
        assertThat(taxi.caution()).isNull();
        assertThat(taxi.fareConfidence()).isEqualTo(TransitResDTO.FareConfidence.CONFIRMED);
        TransitCandidateResDTO.Candidate car = candidateOf(result, TransitMode.CAR);
        assertThat(car.available()).isTrue();
    }

    @Test
    @DisplayName("경로 목록 조회 자체가 실패하면(paths 비어있음) 육로 여부를 모른다고 보고 지금처럼 자차·택시를 만든다")
    void 경로_조회가_실패하면_자차_택시를_그대로_만든다() {
        givenProject(TransportPref.CAR);
        given(blockRepository.findAllByIdInAndProject_IdAndDeletedAtIsNull(List.of(1L, 3L), PROJECT_ID))
                .willReturn(List.of(cheongjuBlock(), jejuBlock()));
        // ODsay 대중교통 경로 조회 자체가 실패한다 — "육로가 없다"가 아니라 "모른다"는 신호다
        given(publicTransitQueryService.getCombinedRoutes(anyDouble(), anyDouble(), anyDouble(), anyDouble()))
                .willThrow(new TransitException(TransitErrorCode.ROUTE_NOT_FOUND));
        given(placeQueryService.getTaxiRoute(anyDouble(), anyDouble(), anyDouble(), anyDouble()))
                .willReturn(Optional.of(new PlaceResDTO.TaxiRoute(556600, 9900, 436642, 356)));

        TransitCandidateResDTO.Result result = service.calculate(PROJECT_ID, List.of(1L, 3L), null);

        TransitCandidateResDTO.Candidate taxi = candidateOf(result, TransitMode.TAXI);
        assertThat(taxi.available()).isTrue();
        assertThat(taxi.fareConfidence()).isEqualTo(TransitResDTO.FareConfidence.CONFIRMED);
        TransitCandidateResDTO.Candidate car = candidateOf(result, TransitMode.CAR);
        assertThat(car.available()).isTrue();
    }

    @Test
    @DisplayName("드라이빙 조회만 실패해 available=false인 후보는 육로 판정과 무관하게 그대로 남는다")
    void 드라이빙_조회만_실패하면_available_false다() {
        givenProject(TransportPref.CAR);
        given(blockRepository.findAllByIdInAndProject_IdAndDeletedAtIsNull(List.of(1L, 2L), PROJECT_ID))
                .willReturn(List.of(blockAt(1L, LAT_A, LNG_A), blockAt(2L, LAT_B, LNG_B)));
        given(publicTransitQueryService.getCombinedRoutes(anyDouble(), anyDouble(), anyDouble(), anyDouble()))
                .willReturn(List.of(busPath()));
        given(placeQueryService.getTaxiRoute(anyDouble(), anyDouble(), anyDouble(), anyDouble()))
                .willReturn(Optional.empty());

        TransitCandidateResDTO.Result result = service.calculate(PROJECT_ID, List.of(1L, 2L), null);

        TransitCandidateResDTO.Candidate taxi = candidateOf(result, TransitMode.TAXI);
        assertThat(taxi.available()).isFalse();
        assertThat(taxi.caution()).isNull();
    }

    @Test
    @DisplayName("육로가 없는 시외 구간은 항공이 기본 후보가 되고 기준 시각 누적에 실제 소요시간을 쓴다")
    void 육로가_없는_시외_구간은_항공이_기본이_된다() {
        given(blockRepository.findAllByIdInAndProject_IdAndDeletedAtIsNull(List.of(2L, 3L), PROJECT_ID))
                .willReturn(List.of(busanBlock(), jejuBlock()));
        given(projectRepository.findByIdAndDeletedAtIsNull(PROJECT_ID)).willReturn(Optional.of(publicProject()));
        given(publicTransitQueryService.getCombinedRoutes(anyDouble(), anyDouble(), anyDouble(), anyDouble()))
                .willReturn(List.of(airPath()));
        given(transitScheduleQueryService.searchTrainStation(anyString())).willReturn(Optional.empty());
        given(transitScheduleQueryService.searchExpressBusTerminal(anyString())).willReturn(Optional.empty());
        given(transitScheduleQueryService.getFlightSchedule(anyInt(), anyInt(), any(LocalDate.class)))
                .willReturn(List.of(TransitScheduleResDTO.FlightSchedule.builder()
                        .airline("대한항공").flightNo("KE1801")
                        .departureTime("10:30").arrivalTime("11:35").runDay("매일")
                        .build()));

        TransitCandidateResDTO.Result result = service.calculate(PROJECT_ID, List.of(2L, 3L), null);

        TransitCandidateResDTO.Segment segment = result.segments().get(0);
        // 육로 대안이 없으니 자차·택시 후보 자체가 없다 — 그렇다고 기본 수단이 null로 비지 않는다
        assertThat(segment.candidates()).extracting(Candidate::mode)
                .doesNotContain(TransitMode.CAR, TransitMode.TAXI)
                .contains(TransitMode.AIR);
        assertThat(segment.defaultMode()).isEqualTo(TransitMode.AIR);
        // 기준 시각 누적(SegmentClock)이 쓸 소요시간도 실제 항공편에서 온다(10:30~11:35 = 65분)
        assertThat(candidateOf(result, TransitMode.AIR).durationMin()).isEqualTo(65);
        verify(placeQueryService, never())
                .getTaxiRoute(anyDouble(), anyDouble(), anyDouble(), anyDouble());
    }

    @Test
    @DisplayName("시외 구간은 수단별로 나뉘고 기준 시각 이후 편이 붙는다")
    void 시외_구간은_수단별로_나뉜다() {
        given(blockRepository.findAllByIdInAndProject_IdAndDeletedAtIsNull(List.of(1L, 2L), PROJECT_ID))
                .willReturn(List.of(seoulBlock(), busanBlock()));
        given(projectRepository.findByIdAndDeletedAtIsNull(PROJECT_ID)).willReturn(Optional.of(publicProject()));
        given(publicTransitQueryService.getCombinedRoutes(anyDouble(), anyDouble(), anyDouble(), anyDouble()))
                .willReturn(List.of(trainPath()));  // pathType=11
        given(transitScheduleQueryService.searchTrainStation("서울"))
                .willReturn(Optional.of(terminal(3300128, "서울")));
        given(transitScheduleQueryService.searchTrainStation("부산"))
                .willReturn(Optional.of(terminal(3300108, "부산")));
        given(transitScheduleQueryService.getTrainSchedule(eq(3300128), eq(3300108), any(LocalDate.class)))
                .willReturn(List.of(
                        train("KTX", 1, "16:00", "18:37", 59800),
                        train("KTX", 15, "16:30", "19:12", 59800),
                        train("무궁화", 1203, "18:10", "23:41", 28600)));

        TransitCandidateResDTO.Result result =
                service.calculate(PROJECT_ID, List.of(1L, 2L), LocalTime.of(14, 0));

        TransitCandidateResDTO.Segment segment = result.segments().get(0);
        assertThat(segment.intercity()).isTrue();
        assertThat(segment.timetableApplied()).isTrue();
        assertThat(segment.referenceAt()).isEqualTo("14:45");
        // 세 수단을 동시에 조회하지만 후보 순서는 기차·고속버스·항공 그대로다
        assertThat(segment.candidates()).extracting(Candidate::mode)
                .startsWith(TransitMode.TRAIN, TransitMode.EXPRESS_BUS, TransitMode.AIR);

        Candidate train = candidateOf(result, TransitMode.TRAIN);
        assertThat(train.departures()).hasSize(3);
        assertThat(train.departures().get(0).departureAt()).isEqualTo("16:00");
        assertThat(train.departures().get(0).fare()).isEqualTo(59800);
        assertThat(train.departures().get(2).labels()).contains("최저 요금");
        // durationMin·fare·arrivalAt이 서로 바뀌어도 잡히도록 구별되는 값을 못 박는다(16:00→18:37은 157분)
        assertThat(train.departures().get(0).name()).isEqualTo("KTX 1");
        assertThat(train.departures().get(0).grade()).isEqualTo("KTX");
        assertThat(train.departures().get(0).arrivalAt()).isEqualTo("18:37");
        assertThat(train.departures().get(0).durationMin()).isEqualTo(157);
        assertThat(train.departures().get(0).fareOptions().general()).isEqualTo(59800);
        assertThat(train.departures().get(0).fareOptions().special()).isEqualTo(99800);
        assertThat(train.departures().get(0).fareOptions().standing()).isEqualTo(54800);
        assertThat(train.departures().get(0).fareConfidence())
                .isEqualTo(TransitResDTO.FareConfidence.CONFIRMED);
        // 후보의 대표값은 첫 편에서 온다
        assertThat(train.durationMin()).isEqualTo(157);
        assertThat(train.fare()).isEqualTo(59800);
    }

    @Test
    @DisplayName("자차·대중교통을 모두 선택하면 시외 구간도 기본 수단을 강제하지 않는다")
    void bothPrefs_segmentDefaultModeIsNull() {
        given(blockRepository.findAllByIdInAndProject_IdAndDeletedAtIsNull(List.of(1L, 2L), PROJECT_ID))
                .willReturn(List.of(seoulBlock(), busanBlock()));
        given(projectRepository.findByIdAndDeletedAtIsNull(PROJECT_ID))
                .willReturn(Optional.of(projectWithPrefs(List.of(TransportPref.CAR, TransportPref.PUBLIC))));
        given(publicTransitQueryService.getCombinedRoutes(anyDouble(), anyDouble(), anyDouble(), anyDouble()))
                .willReturn(List.of(trainPath()));
        given(transitScheduleQueryService.searchTrainStation("서울"))
                .willReturn(Optional.of(terminal(3300128, "서울")));
        given(transitScheduleQueryService.searchTrainStation("부산"))
                .willReturn(Optional.of(terminal(3300108, "부산")));
        given(transitScheduleQueryService.getTrainSchedule(eq(3300128), eq(3300108), any(LocalDate.class)))
                .willReturn(List.of(train("KTX", 1, "16:00", "18:37", 59800)));

        TransitCandidateResDTO.Result result =
                service.calculate(PROJECT_ID, List.of(1L, 2L), LocalTime.of(14, 0));

        assertThat(result.segments())
                .allSatisfy(segment -> assertThat(segment.defaultMode()).isNull());
    }

    @Test
    @DisplayName("기준 시각보다 이른 편은 후보에서 뺀다")
    void 기준_시각_이전_편은_제외한다() {
        given(blockRepository.findAllByIdInAndProject_IdAndDeletedAtIsNull(List.of(1L, 2L), PROJECT_ID))
                .willReturn(List.of(seoulBlock(), busanBlock()));
        given(projectRepository.findByIdAndDeletedAtIsNull(PROJECT_ID)).willReturn(Optional.of(publicProject()));
        given(publicTransitQueryService.getCombinedRoutes(anyDouble(), anyDouble(), anyDouble(), anyDouble()))
                .willReturn(List.of(trainPath()));
        given(transitScheduleQueryService.searchTrainStation(anyString()))
                .willReturn(Optional.of(terminal(3300128, "서울")));
        given(transitScheduleQueryService.getTrainSchedule(anyInt(), anyInt(), any(LocalDate.class)))
                .willReturn(List.of(
                        train("KTX", 1, "05:13", "07:50", 59800),
                        train("KTX", 99, "16:00", "18:37", 59800)));

        TransitCandidateResDTO.Result result =
                service.calculate(PROJECT_ID, List.of(1L, 2L), LocalTime.of(14, 0));

        Candidate train = candidateOf(result, TransitMode.TRAIN);
        assertThat(train.departures()).extracting(d -> d.departureAt()).containsExactly("16:00");
    }

    @Test
    @DisplayName("두 번째 시외 구간은 시간표를 적용하지 않고 이유를 남긴다")
    void 두번째_시외_구간은_시간표를_건너뛴다() {
        given(blockRepository.findAllByIdInAndProject_IdAndDeletedAtIsNull(List.of(1L, 2L, 3L), PROJECT_ID))
                .willReturn(List.of(seoulBlock(), busanBlock(), jejuBlock()));
        given(projectRepository.findByIdAndDeletedAtIsNull(PROJECT_ID)).willReturn(Optional.of(publicProject()));
        given(publicTransitQueryService.getCombinedRoutes(anyDouble(), anyDouble(), anyDouble(), anyDouble()))
                .willReturn(List.of(trainPath()));
        given(transitScheduleQueryService.searchTrainStation(anyString()))
                .willReturn(Optional.of(terminal(3300128, "서울")));
        given(transitScheduleQueryService.getTrainSchedule(anyInt(), anyInt(), any(LocalDate.class)))
                .willReturn(List.of(train("KTX", 1, "16:00", "18:37", 59800)));

        TransitCandidateResDTO.Result result =
                service.calculate(PROJECT_ID, List.of(1L, 2L, 3L), LocalTime.of(9, 0));

        assertThat(result.segments().get(0).timetableApplied()).isTrue();
        assertThat(result.segments().get(1).timetableApplied()).isFalse();
        assertThat(result.segments().get(1).timetableSkipReason())
                .isEqualTo("앞선 시외 구간의 편이 확정되지 않았습니다");
        assertThat(result.segments().get(1).referenceAt()).isNull();
        // 편만 비고 수단은 남는다 — 부산→제주에서 항공이 사라지면 배로 가라는 말이 된다
        assertThat(result.segments().get(1).candidates()).extracting(Candidate::mode)
                .contains(TransitMode.TRAIN, TransitMode.EXPRESS_BUS, TransitMode.AIR);
        Candidate air = result.segments().get(1).candidates().stream()
                .filter(c -> c.mode() == TransitMode.AIR).findFirst().orElseThrow();
        assertThat(air.available()).isTrue();
        assertThat(air.departures()).isEmpty();
        // 탈 편이 없으면 구간도 그리지 않는다 — 0분짜리 leg는 "즉시 도착"으로 읽힌다
        assertThat(air.legs()).isEmpty();
        assertThat(air.durationMin()).isNull();
        // 고를 편이 없는 수단은 기본이 될 수 없다
        assertThat(result.segments().get(1).defaultMode()).isNotIn(
                TransitMode.TRAIN, TransitMode.EXPRESS_BUS, TransitMode.AIR);
    }

    @Test
    @DisplayName("다른 Day의 시외 구간은 앞 Day 때문에 건너뛰지 않고 자기 시간표를 받는다")
    void 다른_Day의_시외_구간은_자기_시간표를_받는다() {
        // Day1: 서울(1)->부산(2). Day2: 부산(2)->제주(3) — blockIds에 2를 두 번 이어 붙여
        // "이동 없는" 경계 쌍으로 만든다(pairsOf는 같은 id가 연달아 오면 구간을 만들지 않는다).
        // 실제로는 요청 크기 상한 30의 근거("3일 일정에 Day당 10블록")대로 여러 Day를 한
        // 체인으로 보낸 상황을 재현한다.
        given(blockRepository.findAllByIdInAndProject_IdAndDeletedAtIsNull(List.of(1L, 2L, 2L, 3L), PROJECT_ID))
                .willReturn(List.of(
                        blockAt(1L, LAT_SEOUL, LNG_SEOUL, 1),
                        blockAt(2L, LAT_BUSAN, LNG_BUSAN, 2),
                        blockAt(3L, LAT_JEJU, LNG_JEJU, 2)));
        given(projectRepository.findByIdAndDeletedAtIsNull(PROJECT_ID)).willReturn(Optional.of(publicProject()));
        given(publicTransitQueryService.getCombinedRoutes(anyDouble(), anyDouble(), anyDouble(), anyDouble()))
                .willReturn(List.of(trainPath()));
        given(transitScheduleQueryService.searchTrainStation(anyString()))
                .willReturn(Optional.of(terminal(3300128, "서울")));
        given(transitScheduleQueryService.getTrainSchedule(anyInt(), anyInt(), any(LocalDate.class)))
                .willReturn(List.of(train("KTX", 1, "16:00", "18:37", 59800)));

        TransitCandidateResDTO.Result result =
                service.calculate(PROJECT_ID, List.of(1L, 2L, 2L, 3L), LocalTime.of(9, 0));

        assertThat(result.segments()).hasSize(2);
        TransitCandidateResDTO.Segment day1Segment = result.segments().get(0);
        TransitCandidateResDTO.Segment day2Segment = result.segments().get(1);

        assertThat(day1Segment.timetableApplied()).isTrue();
        assertThat(day1Segment.referenceAt()).isEqualTo("09:45");

        // 고쳐지기 전에는 day2Segment가 timetableApplied=false·
        // skipReason="앞선 시외 구간의 편이 확정되지 않았습니다"·referenceAt=null이었다.
        assertThat(day2Segment.timetableApplied()).isTrue();
        assertThat(day2Segment.timetableSkipReason()).isNull();
        // Day2도 자기 dayStart(09:00)에서 새로 45분 버퍼를 계산한다 — Day1의 누적 시각을
        // 물려받지 않는다.
        assertThat(day2Segment.referenceAt()).isEqualTo("09:45");
        // Day마다 시간표를 한 번씩, 총 두 번 조회한다
        verify(transitScheduleQueryService, times(2))
                .getTrainSchedule(anyInt(), anyInt(), any(LocalDate.class));
    }

    @Test
    @DisplayName("두 번째 Day의 시간표 조회는 새 20초가 아니라 첫 Day가 쓰고 남은 예산만 받는다")
    void 두번째_Day는_새_예산이_아니라_남은_예산을_받는다() throws InterruptedException {
        // 진짜 20초를 기다리지 않기 위해 예산을 잠깐 줄인다 — TIMETABLE_TIMEOUT은 이 테스트를
        // 위해 package-private·非final로 뒀다.
        Duration original = TransitCandidateServiceImpl.TIMETABLE_TIMEOUT;
        TransitCandidateServiceImpl.TIMETABLE_TIMEOUT = Duration.ofMillis(600);
        try {
            given(blockRepository.findAllByIdInAndProject_IdAndDeletedAtIsNull(List.of(1L, 2L, 2L, 3L), PROJECT_ID))
                    .willReturn(List.of(
                            blockAt(1L, LAT_SEOUL, LNG_SEOUL, 1),
                            blockAt(2L, LAT_BUSAN, LNG_BUSAN, 2),
                            blockAt(3L, LAT_JEJU, LNG_JEJU, 2)));
            given(projectRepository.findByIdAndDeletedAtIsNull(PROJECT_ID)).willReturn(Optional.of(publicProject()));
            given(publicTransitQueryService.getCombinedRoutes(anyDouble(), anyDouble(), anyDouble(), anyDouble()))
                    .willReturn(List.of(trainPath()));
            given(transitScheduleQueryService.searchTrainStation(anyString()))
                    .willReturn(Optional.of(terminal(3300128, "서울")));
            given(transitScheduleQueryService.searchExpressBusTerminal(anyString())).willReturn(Optional.empty());
            // Day1은 350ms 걸려도 자기 예산(600ms, 새 예산) 안에 끝나 정상 응답한다. 그 350ms를
            // 쓰고 나면 공유 예산에는 약 250ms만 남는다. Day2는 450ms가 걸리는데, 이건 "남은
            // ~250ms"로는 못 끝내지만(→ 취소되어 unavailable) "새 600ms"라면 넉넉히 끝난다
            // (→ available) — 그래서 이 둘의 결과가 갈리는 것 자체가 예산이 Day끼리 공유되는지
            // 아닌지를 실측으로 가른다.
            given(transitScheduleQueryService.getTrainSchedule(anyInt(), anyInt(), any(LocalDate.class)))
                    .willAnswer(invocation -> {
                        Thread.sleep(350);
                        return List.of(train("KTX", 1, "16:00", "18:37", 59800));
                    })
                    .willAnswer(invocation -> {
                        Thread.sleep(450);
                        return List.of(train("KTX", 2, "17:00", "19:37", 59800));
                    });

            TransitCandidateResDTO.Result result =
                    service.calculate(PROJECT_ID, List.of(1L, 2L, 2L, 3L), LocalTime.of(9, 0));

            assertThat(result.segments()).hasSize(2);
            Candidate day1Train = result.segments().get(0).candidates().stream()
                    .filter(c -> c.mode() == TransitMode.TRAIN).findFirst().orElseThrow();
            Candidate day2Train = result.segments().get(1).candidates().stream()
                    .filter(c -> c.mode() == TransitMode.TRAIN).findFirst().orElseThrow();

            assertThat(day1Train.available()).as("Day1은 자기 예산(600ms) 안에 끝난다").isTrue();
            assertThat(day1Train.departures()).isNotEmpty();
            // 고쳐지기 전(Day마다 새 20초)이었다면 450ms < 600ms라 Day2도 available=true였다.
            // 남은 예산(~250ms)을 받는 지금은 450ms가 그 예산을 넘겨 취소된다.
            assertThat(day2Train.available())
                    .as("Day2는 Day1이 쓰고 남은 예산만 받아 시간 안에 못 끝나고 취소된다")
                    .isFalse();
        } finally {
            TransitCandidateServiceImpl.TIMETABLE_TIMEOUT = original;
        }
    }

    @Test
    @DisplayName("시간표는 첫 시외 구간에서 한 번만 조회한다 — 기준 시각이 두 번째 구간을 감당하지 못한다")
    void 시간표는_한_구간에만_조회한다() {
        given(blockRepository.findAllByIdInAndProject_IdAndDeletedAtIsNull(List.of(1L, 2L, 3L), PROJECT_ID))
                .willReturn(List.of(seoulBlock(), busanBlock(), jejuBlock()));
        given(projectRepository.findByIdAndDeletedAtIsNull(PROJECT_ID)).willReturn(Optional.of(publicProject()));
        given(publicTransitQueryService.getCombinedRoutes(anyDouble(), anyDouble(), anyDouble(), anyDouble()))
                .willReturn(List.of(trainPath()));
        given(transitScheduleQueryService.searchTrainStation(anyString()))
                .willReturn(Optional.of(terminal(3300128, "서울")));
        given(transitScheduleQueryService.getTrainSchedule(anyInt(), anyInt(), any(LocalDate.class)))
                .willReturn(List.of(train("KTX", 1, "16:00", "18:37", 59800)));

        service.calculate(PROJECT_ID, List.of(1L, 2L, 3L), LocalTime.of(9, 0));

        // SegmentClock은 실제 탑승 시각(16:00)이 아니라 기준 시각(09:45)에서 이동시간만 누적한다.
        // 두 번째 시외 구간에 그 커서로 다시 기준 시각을 뽑으면 이미 지나간 시각을 준다 —
        // intercityUsed 가드가 그것을 막는다. 이 verify가 가드가 사라지면 깨진다(2회가 된다).
        verify(transitScheduleQueryService, times(1))
                .getTrainSchedule(anyInt(), anyInt(), any(LocalDate.class));
        verify(transitScheduleQueryService, times(2)).searchTrainStation(anyString());
    }

    @Test
    @DisplayName("dayStart가 없으면 09:00을 기준으로 삼는다")
    void dayStart가_없으면_아홉시다() {
        given(blockRepository.findAllByIdInAndProject_IdAndDeletedAtIsNull(List.of(1L, 2L), PROJECT_ID))
                .willReturn(List.of(seoulBlock(), busanBlock()));
        given(projectRepository.findByIdAndDeletedAtIsNull(PROJECT_ID)).willReturn(Optional.of(publicProject()));
        given(publicTransitQueryService.getCombinedRoutes(anyDouble(), anyDouble(), anyDouble(), anyDouble()))
                .willReturn(List.of(trainPath()));
        given(transitScheduleQueryService.searchTrainStation(anyString()))
                .willReturn(Optional.of(terminal(3300128, "서울")));
        given(transitScheduleQueryService.getTrainSchedule(anyInt(), anyInt(), any(LocalDate.class)))
                .willReturn(List.of(train("KTX", 1, "16:00", "18:37", 59800)));

        TransitCandidateResDTO.Result result = service.calculate(PROJECT_ID, List.of(1L, 2L), null);

        assertThat(result.segments().get(0).referenceAt()).isEqualTo("09:45");
    }

    @Test
    @DisplayName("역 이름을 못 찾으면 그 수단만 제외하고 나머지는 유지한다")
    void 역_검색_실패는_그_수단만_뺀다() {
        given(blockRepository.findAllByIdInAndProject_IdAndDeletedAtIsNull(List.of(1L, 2L), PROJECT_ID))
                .willReturn(List.of(seoulBlock(), busanBlock()));
        given(projectRepository.findByIdAndDeletedAtIsNull(PROJECT_ID)).willReturn(Optional.of(publicProject()));
        given(publicTransitQueryService.getCombinedRoutes(anyDouble(), anyDouble(), anyDouble(), anyDouble()))
                .willReturn(List.of(trainPath()));
        given(transitScheduleQueryService.searchTrainStation(anyString())).willReturn(Optional.empty());
        given(placeQueryService.getTaxiRoute(anyDouble(), anyDouble(), anyDouble(), anyDouble()))
                .willReturn(Optional.of(new PlaceResDTO.TaxiRoute(374600, 0, 401839, 310)));

        TransitCandidateResDTO.Result result =
                service.calculate(PROJECT_ID, List.of(1L, 2L), LocalTime.of(9, 0));

        Candidate train = candidateOf(result, TransitMode.TRAIN);
        assertThat(train.available()).isFalse();
        assertThat(candidateOf(result, TransitMode.TAXI).available()).isTrue();
    }

    @Test
    @DisplayName("기준 시각 이후 편이 없으면 available=true에 빈 departures다")
    void 남은_편이_없으면_빈_목록이다() {
        given(blockRepository.findAllByIdInAndProject_IdAndDeletedAtIsNull(List.of(1L, 2L), PROJECT_ID))
                .willReturn(List.of(seoulBlock(), busanBlock()));
        given(projectRepository.findByIdAndDeletedAtIsNull(PROJECT_ID)).willReturn(Optional.of(publicProject()));
        given(publicTransitQueryService.getCombinedRoutes(anyDouble(), anyDouble(), anyDouble(), anyDouble()))
                .willReturn(List.of(trainPath()));
        given(transitScheduleQueryService.searchTrainStation(anyString()))
                .willReturn(Optional.of(terminal(3300128, "서울")));
        given(transitScheduleQueryService.getTrainSchedule(anyInt(), anyInt(), any(LocalDate.class)))
                .willReturn(List.of(train("KTX", 1, "05:13", "07:50", 59800)));

        TransitCandidateResDTO.Result result =
                service.calculate(PROJECT_ID, List.of(1L, 2L), LocalTime.of(22, 0));

        Candidate train = candidateOf(result, TransitMode.TRAIN);
        assertThat(train.available()).isTrue();
        assertThat(train.departures()).isEmpty();
    }

    @Test
    @DisplayName("시내 구간은 후보 5개에 라벨과 legs가 붙는다")
    void 시내_구간은_다중_후보다() {
        given(blockRepository.findAllByIdInAndProject_IdAndDeletedAtIsNull(List.of(1L, 2L), PROJECT_ID))
                .willReturn(List.of(blockA(), blockB()));
        given(projectRepository.findByIdAndDeletedAtIsNull(PROJECT_ID)).willReturn(Optional.of(publicProject()));
        given(publicTransitQueryService.getCombinedRoutes(anyDouble(), anyDouble(), anyDouble(), anyDouble()))
                .willReturn(List.of(busPath(), subwayPath(), cheapPath()));

        TransitCandidateResDTO.Result result =
                service.calculate(PROJECT_ID, List.of(1L, 2L), LocalTime.of(9, 0));

        TransitCandidateResDTO.Segment segment = result.segments().get(0);
        assertThat(segment.intercity()).isFalse();
        List<Candidate> transit = segment.candidates().stream()
                .filter(c -> c.mode() == TransitMode.TRANSIT).toList();
        assertThat(transit).hasSizeBetween(1, 5);
        assertThat(transit.get(0).legs()).isNotEmpty();
        assertThat(transit.stream().anyMatch(c -> c.labels().contains("최저 요금"))).isTrue();
    }

    @Test
    @DisplayName("시외 경로가 섞인 구간은 시내 선정기에 닿지 않는다")
    void 시외_경로는_시내_선정기에_닿지_않는다() {
        // ODsay가 시내 경로(pathType=1)와 기차 경로(11)를 한 응답에 섞어 준다
        시외_경로가_섞이면_선정기를_부르지_않는다(trainPath());
    }

    @Test
    @DisplayName("해운 경로(pathType=14)도 시내 선정기에 닿지 않는다")
    void 해운_경로는_시내_선정기에_닿지_않는다() {
        // 해운은 요금도 환승 수도 없어 기차와 똑같은 누출 프로필이다. pathType 목록에서 빠지기 쉬워 따로 못 박는다
        시외_경로가_섞이면_선정기를_부르지_않는다(ferryPath());
    }

    private void 시외_경로가_섞이면_선정기를_부르지_않는다(OdsayRouteResponse.Path intercityPath) {
        given(blockRepository.findAllByIdInAndProject_IdAndDeletedAtIsNull(List.of(1L, 2L), PROJECT_ID))
                .willReturn(List.of(seoulBlock(), busanBlock()));
        given(projectRepository.findByIdAndDeletedAtIsNull(PROJECT_ID)).willReturn(Optional.of(publicProject()));
        given(publicTransitQueryService.getCombinedRoutes(anyDouble(), anyDouble(), anyDouble(), anyDouble()))
                .willReturn(List.of(busPath(), intercityPath));
        given(transitScheduleQueryService.searchTrainStation(anyString())).willReturn(Optional.empty());

        TransitCandidateResDTO.Result result =
                service.calculate(PROJECT_ID, List.of(1L, 2L), LocalTime.of(9, 0));

        TransitCandidateResDTO.Segment segment = result.segments().get(0);
        assertThat(segment.intercity()).isTrue();
        // 시외 경로는 payment가 null이고 환승 수도 없다 — 시내 선정기에 들어가면 "환승 최소" 축을
        // 환승 0으로 이겨 버린다. 선정기 자체를 부르지 않는 것이 그 보장이다.
        verify(routeSelector, never()).selectTop5(anyList());
        assertThat(segment.candidates()).extracting(Candidate::mode)
                .doesNotContain(TransitMode.TRANSIT);
    }

    @Test
    @DisplayName("요금 없는 고속버스편은 0원 확정이 아니라 UNKNOWN이다")
    void 요금_없는_고속버스편은_확정이_아니다() {
        given(blockRepository.findAllByIdInAndProject_IdAndDeletedAtIsNull(List.of(1L, 2L), PROJECT_ID))
                .willReturn(List.of(seoulBlock(), busanBlock()));
        given(projectRepository.findByIdAndDeletedAtIsNull(PROJECT_ID)).willReturn(Optional.of(publicProject()));
        given(publicTransitQueryService.getCombinedRoutes(anyDouble(), anyDouble(), anyDouble(), anyDouble()))
                .willReturn(List.of(trainPath()));
        given(transitScheduleQueryService.searchExpressBusTerminal(anyString()))
                .willReturn(Optional.of(terminal(4000057, "서울고속버스터미널")));
        // 편이 셋이어야 selectThree가 최저가 축까지 실제로 돈다 — 둘이면 시각순에서 끝나
        // "최저 요금 라벨이 없다"는 단정이 저절로 통과해 버린다
        given(transitScheduleQueryService.getIntercityBusSchedule(anyInt(), anyInt(), any(LocalDate.class)))
                .willReturn(List.of(
                        bus(2, "16:00", 240, 39700),
                        bus(1, "17:00", null, null),
                        bus(1, "18:00", 300, null)));

        TransitCandidateResDTO.Result result =
                service.calculate(PROJECT_ID, List.of(1L, 2L), LocalTime.of(9, 0));

        Candidate expressBus = candidateOf(result, TransitMode.EXPRESS_BUS);
        TransitCandidateResDTO.Departure known = expressBus.departures().get(0);
        assertThat(known.fare()).isEqualTo(39700);
        assertThat(known.fareConfidence()).isEqualTo(TransitResDTO.FareConfidence.CONFIRMED);
        assertThat(known.grade()).isEqualTo("우등");
        assertThat(known.arrivalAt()).isEqualTo("20:00");
        assertThat(known.durationMin()).isEqualTo(240);

        TransitCandidateResDTO.Departure unknown = expressBus.departures().get(1);
        assertThat(unknown.fare()).isNull();
        assertThat(unknown.fareConfidence()).isEqualTo(TransitResDTO.FareConfidence.UNKNOWN);
        assertThat(unknown.grade()).isEqualTo("일반");
        // 소요시간을 모르면 도착 시각도 지어내지 않는다
        assertThat(unknown.arrivalAt()).isNull();
        assertThat(unknown.durationMin()).isNull();
        // 요금 없는 편이 0원으로 읽혀 최저가 라벨을 훔치면 안 된다
        assertThat(expressBus.departures())
                .allSatisfy(d -> assertThat(d.labels()).doesNotContain("최저 요금"));
    }

    @Test
    @DisplayName("시작일이 없는 프로젝트는 시간표를 붙이지 않고 이유를 남긴다")
    void 시작일이_없으면_시간표를_건너뛴다() {
        given(blockRepository.findAllByIdInAndProject_IdAndDeletedAtIsNull(List.of(1L, 2L), PROJECT_ID))
                .willReturn(List.of(seoulBlock(), busanBlock()));
        givenProject(TransportPref.PUBLIC);  // startDate가 없는 프로젝트
        given(publicTransitQueryService.getCombinedRoutes(anyDouble(), anyDouble(), anyDouble(), anyDouble()))
                .willReturn(List.of(trainPath()));

        TransitCandidateResDTO.Result result =
                service.calculate(PROJECT_ID, List.of(1L, 2L), LocalTime.of(9, 0));

        TransitCandidateResDTO.Segment segment = result.segments().get(0);
        assertThat(segment.intercity()).isTrue();
        assertThat(segment.timetableApplied()).isFalse();
        assertThat(segment.timetableSkipReason())
                .isEqualTo("프로젝트 시작일이 없어 운행 요일을 확인할 수 없습니다");
        assertThat(segment.referenceAt()).isNull();
        // 오늘 요일로 편을 거르고 요금을 고르느니 아예 묻지 않는다
        verifyNoInteractions(transitScheduleQueryService);
        // 수단 슬롯은 남되 탈 편이 정해지지 않았으므로 구간(leg)도 그리지 않는다
        Candidate train = candidateOf(result, TransitMode.TRAIN);
        assertThat(train.departures()).isEmpty();
        assertThat(train.legs()).isEmpty();
        assertThat(train.durationMin()).isNull();
    }

    @Test
    @DisplayName("시작일이 없으면 시외 구간마다 같은 이유가 붙는다 — 뒤 구간에 '앞 구간 때문'이 아니다")
    void 시작일이_없으면_모든_시외_구간에_같은_이유가_붙는다() {
        given(blockRepository.findAllByIdInAndProject_IdAndDeletedAtIsNull(List.of(1L, 2L, 3L), PROJECT_ID))
                .willReturn(List.of(seoulBlock(), busanBlock(), jejuBlock()));
        givenProject(TransportPref.PUBLIC);  // startDate가 없는 프로젝트
        given(publicTransitQueryService.getCombinedRoutes(anyDouble(), anyDouble(), anyDouble(), anyDouble()))
                .willReturn(List.of(trainPath()));

        TransitCandidateResDTO.Result result =
                service.calculate(PROJECT_ID, List.of(1L, 2L, 3L), LocalTime.of(9, 0));

        // 첫 구간에서 시간표를 붙인 적이 없으므로 intercityUsed가 서지 않는다 — 두 번째 구간도
        // "앞선 시외 구간의 편이 확정되지 않았습니다"가 아니라 진짜 이유를 받아야 한다
        assertThat(result.segments()).hasSize(2);
        assertThat(result.segments()).allSatisfy(segment -> {
            assertThat(segment.intercity()).isTrue();
            assertThat(segment.timetableApplied()).isFalse();
            assertThat(segment.timetableSkipReason())
                    .isEqualTo("프로젝트 시작일이 없어 운행 요일을 확인할 수 없습니다");
        });
    }

    private Block cheongjuBlock() {
        return blockAt(1L, LAT_CHEONGJU, LNG_CHEONGJU);
    }

    /** id가 3인 이유는 서울(1)→부산(2)→제주(3) 3구간 시나리오와 같은 블록을 쓰기 위해서다 */
    private Block jejuBlock() {
        return blockAt(3L, LAT_JEJU, LNG_JEJU);
    }

    private Block seoulBlock() {
        return blockAt(1L, LAT_SEOUL, LNG_SEOUL);
    }

    private Block busanBlock() {
        return blockAt(2L, LAT_BUSAN, LNG_BUSAN);
    }

    private Block blockA() {
        return blockAt(1L, LAT_A, LNG_A);
    }

    private Block blockB() {
        return blockAt(2L, LAT_B, LNG_B);
    }

    /** 시외 구간의 여행 날짜(startDate + dayNo-1)를 유도할 수 있는 프로젝트 */
    private Project publicProject() {
        return Project.builder()
                .transportPrefs(List.of(TransportPref.PUBLIC))
                .startDate(LocalDate.of(2026, 8, 10))
                .build();
    }

    /** 임의의 선호 목록(다중 선택 포함)을 가진 프로젝트. 시외 구간 여행 날짜는 publicProject()와 동일 */
    private Project projectWithPrefs(List<TransportPref> prefs) {
        return Project.builder()
                .transportPrefs(prefs)
                .startDate(LocalDate.of(2026, 8, 10))
                .build();
    }

    /** 기차 경로(pathType=11). ODsay는 시외 경로에 payment를 주지 않는다 */
    private OdsayRouteResponse.Path trainPath() {
        return new OdsayRouteResponse.Path(11,
                new OdsayRouteResponse.Info(157, null, null, 325000, 800, null, null, "서울", "부산"),
                List.of(new OdsayRouteResponse.SubPath(4, 157, 325000, "서울", "부산", null)));
    }

    /** 해운 경로(pathType=14). 기차와 마찬가지로 요금도 환승 수도 없다 */
    private OdsayRouteResponse.Path ferryPath() {
        return new OdsayRouteResponse.Path(14,
                new OdsayRouteResponse.Info(150, null, null, 98000, 600, null, null, "목포", "홍도"),
                List.of(new OdsayRouteResponse.SubPath(7, 150, 98000, "목포항", "홍도항", null)));
    }

    /** 버스(육로)로 항구까지 간 다음 배를 타는 복합 경로(pathType=20). 일부만 육로여도 경로 전체는 비육로다 */
    private OdsayRouteResponse.Path busPlusFerryPath() {
        return new OdsayRouteResponse.Path(20,
                new OdsayRouteResponse.Info(200, null, null, 120000, 700, null, null, "목포", "홍도"),
                List.of(
                        new OdsayRouteResponse.SubPath(2, 30, 15000, "목포터미널", "목포항", null),
                        new OdsayRouteResponse.SubPath(7, 150, 98000, "목포항", "홍도항", null)));
    }

    /** 지하철로 이어지는 시내 경로 — 가장 빠르다 */
    private OdsayRouteResponse.Path subwayPath() {
        return new OdsayRouteResponse.Path(1,
                new OdsayRouteResponse.Info(28, 1600, 4, 11200, 500, 0, 1, "시청", "강남역"),
                List.of(new OdsayRouteResponse.SubPath(1, 28, 11200, "시청역", "강남역", null)));
    }

    /** 느리지만 가장 싼 시내 경로 */
    private OdsayRouteResponse.Path cheapPath() {
        return new OdsayRouteResponse.Path(1,
                new OdsayRouteResponse.Info(51, 1200, 12, 12900, 900, 2, 0, "시청", "역삼"),
                List.of(new OdsayRouteResponse.SubPath(2, 51, 12900, "시청앞", "역삼역", null)));
    }

    private TransitScheduleResDTO.TerminalSearchResult terminal(int stationId, String stationName) {
        return TransitScheduleResDTO.TerminalSearchResult.builder()
                .stationId(stationId)
                .stationName(stationName)
                .lat(0)
                .lng(0)
                .destinations(List.of())
                .build();
    }

    private TransitScheduleResDTO.BusSchedule bus(
            int busClass, String departureTime, Integer wasteTimeMin, Integer fare) {
        return TransitScheduleResDTO.BusSchedule.builder()
                .busClass(busClass)
                .departureTime(departureTime)
                .wasteTimeMin(wasteTimeMin)
                .fare(fare)
                .build();
    }

    private TransitScheduleResDTO.TrainSchedule train(
            String trainClass, int trainNo, String departureTime, String arrivalTime, Integer generalFare) {
        return TransitScheduleResDTO.TrainSchedule.builder()
                .railName("경부선")
                .trainClass(trainClass)
                .trainNo(trainNo)
                .departureTime(departureTime)
                .arrivalTime(arrivalTime)
                .runDay("매일")
                .generalFare(generalFare)
                .specialFare(generalFare + 40_000)
                .standingFare(generalFare - 5_000)
                .build();
    }

    /** 항공 구간이 섞인 경로. trafficType=6(AIR)이 있으면 육로로 이어지지 않는다는 신호다 */
    private OdsayRouteResponse.Path airPath() {
        return new OdsayRouteResponse.Path(20,
                new OdsayRouteResponse.Info(356, null, null, 436642, null, null, null, "청주", "제주"),
                List.of(new OdsayRouteResponse.SubPath(6, 356, 436642, "청주공항", "제주공항", null)));
    }

    /** 버스로만 이어지는 순수 육로 경로 */
    private OdsayRouteResponse.Path busPath() {
        return new OdsayRouteResponse.Path(1,
                new OdsayRouteResponse.Info(32, 14900, 10, 10327, 200, 0, 0, "시청", "강남역"),
                List.of(new OdsayRouteResponse.SubPath(2, 32, 10327, "시청", "강남역", null)));
    }

    private TransitCandidateResDTO.Candidate candidateOf(TransitCandidateResDTO.Result result, TransitMode mode) {
        return result.segments().get(0).candidates().stream()
                .filter(c -> c.mode() == mode)
                .findFirst().orElseThrow();
    }
}
