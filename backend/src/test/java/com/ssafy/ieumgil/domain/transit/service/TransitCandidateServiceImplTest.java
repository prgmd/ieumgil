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
import com.ssafy.ieumgil.domain.transit.dto.TransitCandidateResDTO;
import com.ssafy.ieumgil.domain.transit.dto.TransitCandidateResDTO.TransitMode;
import com.ssafy.ieumgil.domain.transit.dto.TransitResDTO;
import com.ssafy.ieumgil.domain.transit.exception.TransitErrorCode;
import com.ssafy.ieumgil.domain.transit.exception.TransitException;
import com.ssafy.ieumgil.global.exception.CustomException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.anyDouble;
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

    private static final Long PROJECT_ID = 10L;

    private Block blockAt(long id, double lat, double lng) {
        return Block.builder()
                .id(id).dayNo(1).orderKey("a" + id).name("블록" + id)
                .category(BlockCategory.SPOT).durationMin(60).budget(0)
                .lat(BigDecimal.valueOf(lat)).lng(BigDecimal.valueOf(lng))
                .source(BlockSource.KAKAO)
                .build();
    }

    private void givenProject(TransportPref pref) {
        given(projectRepository.findByIdAndDeletedAtIsNull(PROJECT_ID))
                .willReturn(Optional.of(Project.builder().transportPref(pref).build()));
    }

    @Test
    @DisplayName("PUBLIC 프로젝트의 먼 구간은 대중교통이 기본이다")
    void publicPrefDefaultsToTransit() {
        givenProject(TransportPref.PUBLIC);
        given(blockRepository.findAllByIdInAndProject_IdAndDeletedAtIsNull(List.of(1L, 2L), PROJECT_ID))
                .willReturn(List.of(blockAt(1L, LAT_A, LNG_A), blockAt(2L, LAT_B, LNG_B)));
        given(publicTransitQueryService.getCombinedRoute(LAT_A, LNG_A, LAT_B, LNG_B))
                .willReturn(TransitResDTO.Route.builder()
                        .durationMin(25).fare(1400).intervalMin(8)
                        .fareConfidence(TransitResDTO.FareConfidence.CONFIRMED).build());
        given(placeQueryService.getTaxiRoute(LAT_A, LNG_A, LAT_B, LNG_B))
                .willReturn(Optional.of(new PlaceResDTO.TaxiRoute(8900, 0, 6800, 12)));

        TransitCandidateResDTO.Result result = service.calculate(PROJECT_ID, List.of(1L, 2L));

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
        assertThat(transit.distanceM()).isNull();  // TransitResDTO.Route는 거리를 안 준다
    }

    @Test
    @DisplayName("CAR 프로젝트의 먼 구간은 자차가 기본이다 — 대중교통은 후보에도 없다")
    void carPrefDefaultsToCarAndExcludesTransit() {
        givenProject(TransportPref.CAR);
        given(blockRepository.findAllByIdInAndProject_IdAndDeletedAtIsNull(List.of(1L, 2L), PROJECT_ID))
                .willReturn(List.of(blockAt(1L, LAT_A, LNG_A), blockAt(2L, LAT_B, LNG_B)));
        given(placeQueryService.getTaxiRoute(LAT_A, LNG_A, LAT_B, LNG_B))
                .willReturn(Optional.of(new PlaceResDTO.TaxiRoute(8900, 0, 6800, 12)));

        TransitCandidateResDTO.Result result = service.calculate(PROJECT_ID, List.of(1L, 2L));

        TransitCandidateResDTO.Segment segment = result.segments().get(0);
        assertThat(segment.defaultMode()).isEqualTo(TransitMode.CAR);
        assertThat(segment.candidates()).extracting(TransitCandidateResDTO.Candidate::mode)
                .containsExactly(TransitMode.CAR, TransitMode.TAXI);
        verify(publicTransitQueryService, never())
                .getCombinedRoute(anyDouble(), anyDouble(), anyDouble(), anyDouble());
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

        TransitCandidateResDTO.Result result = service.calculate(PROJECT_ID, List.of(1L, 2L));

        TransitCandidateResDTO.Segment segment = result.segments().get(0);
        assertThat(segment.defaultMode()).isEqualTo(TransitMode.WALK);
        assertThat(segment.candidates()).extracting(TransitCandidateResDTO.Candidate::mode)
                .containsExactly(TransitMode.WALK);
        verify(publicTransitQueryService, never())
                .getCombinedRoute(anyDouble(), anyDouble(), anyDouble(), anyDouble());
        verify(placeQueryService, never())
                .getTaxiRoute(anyDouble(), anyDouble(), anyDouble(), anyDouble());
    }

    @Test
    @DisplayName("2km를 넘으면 도보는 후보에서 빠진다 — available=false가 아니라 아예 없다")
    void farSegmentHasNoWalkCandidate() {
        givenProject(TransportPref.PUBLIC);
        given(blockRepository.findAllByIdInAndProject_IdAndDeletedAtIsNull(List.of(1L, 2L), PROJECT_ID))
                .willReturn(List.of(blockAt(1L, LAT_A, LNG_A), blockAt(2L, LAT_B, LNG_B)));
        given(publicTransitQueryService.getCombinedRoute(LAT_A, LNG_A, LAT_B, LNG_B))
                .willReturn(TransitResDTO.Route.builder()
                        .durationMin(25).fare(1400).intervalMin(8)
                        .fareConfidence(TransitResDTO.FareConfidence.CONFIRMED).build());
        given(placeQueryService.getTaxiRoute(LAT_A, LNG_A, LAT_B, LNG_B))
                .willReturn(Optional.of(new PlaceResDTO.TaxiRoute(8900, 0, 6800, 12)));

        TransitCandidateResDTO.Result result = service.calculate(PROJECT_ID, List.of(1L, 2L));

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
        given(publicTransitQueryService.getCombinedRoute(LAT_A, LNG_A, LAT_B, LNG_B))
                .willThrow(new TransitException(TransitErrorCode.ROUTE_NOT_FOUND));
        given(placeQueryService.getTaxiRoute(LAT_A, LNG_A, LAT_B, LNG_B))
                .willReturn(Optional.of(new PlaceResDTO.TaxiRoute(8900, 0, 6800, 12)));

        TransitCandidateResDTO.Result result = service.calculate(PROJECT_ID, List.of(1L, 2L));

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
        given(publicTransitQueryService.getCombinedRoute(LAT_A, LNG_A, LAT_B, LNG_B))
                .willThrow(new TransitException(TransitErrorCode.ROUTE_NOT_FOUND));
        given(placeQueryService.getTaxiRoute(LAT_A, LNG_A, LAT_B, LNG_B))
                .willReturn(Optional.empty());

        TransitCandidateResDTO.Result result = service.calculate(PROJECT_ID, List.of(1L, 2L));

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
        given(publicTransitQueryService.getCombinedRoute(LAT_A, LNG_A, LAT_B, LNG_B))
                .willReturn(TransitResDTO.Route.builder()
                        .durationMin(25).fare(1400).intervalMin(8)
                        .fareConfidence(TransitResDTO.FareConfidence.CONFIRMED).build());
        given(publicTransitQueryService.getCombinedRoute(LAT_B, LNG_B, LAT_A, LNG_A))
                .willReturn(TransitResDTO.Route.builder()
                        .durationMin(27).fare(1400).intervalMin(8)
                        .fareConfidence(TransitResDTO.FareConfidence.CONFIRMED).build());
        given(placeQueryService.getTaxiRoute(LAT_A, LNG_A, LAT_B, LNG_B))
                .willReturn(Optional.of(new PlaceResDTO.TaxiRoute(8900, 0, 6800, 12)));
        given(placeQueryService.getTaxiRoute(LAT_B, LNG_B, LAT_A, LNG_A))
                .willReturn(Optional.of(new PlaceResDTO.TaxiRoute(9100, 0, 6800, 13)));

        TransitCandidateResDTO.Result result = service.calculate(PROJECT_ID, List.of(1L, 2L, 1L, 2L));

        assertThat(result.segments()).hasSize(3);
        verify(publicTransitQueryService, times(1)).getCombinedRoute(LAT_A, LNG_A, LAT_B, LNG_B);
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

        TransitCandidateResDTO.Result result = service.calculate(PROJECT_ID, List.of(1L, 2L));

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

        assertThatThrownBy(() -> service.calculate(PROJECT_ID, List.of(1L, 2L)))
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

        assertThatThrownBy(() -> service.calculate(PROJECT_ID, List.of(1L, 2L)))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("code", TransitErrorCode.COORDINATE_REQUIRED);
    }

    @Test
    @DisplayName("블록이 하나뿐이면 구간이 없다")
    void singleBlockYieldsNoSegments() {
        givenProject(TransportPref.PUBLIC);
        given(blockRepository.findAllByIdInAndProject_IdAndDeletedAtIsNull(List.of(1L), PROJECT_ID))
                .willReturn(List.of(blockAt(1L, LAT_A, LNG_A)));

        TransitCandidateResDTO.Result result = service.calculate(PROJECT_ID, List.of(1L));

        assertThat(result.segments()).isEmpty();
        verifyNoInteractions(publicTransitQueryService);
        verifyNoInteractions(placeQueryService);
    }

    @Test
    @DisplayName("300m~2km 중간 대역의 PUBLIC 프로젝트는 도보가 아니라 대중교통이 기본이다")
    void midRangeSegmentKeepsPreferredModeAsDefaultForPublic() {
        givenProject(TransportPref.PUBLIC);
        given(blockRepository.findAllByIdInAndProject_IdAndDeletedAtIsNull(List.of(1L, 2L), PROJECT_ID))
                .willReturn(List.of(blockAt(1L, LAT_A, LNG_A), blockAt(2L, LAT_MID, LNG_A)));
        given(publicTransitQueryService.getCombinedRoute(LAT_A, LNG_A, LAT_MID, LNG_A))
                .willReturn(TransitResDTO.Route.builder()
                        .durationMin(9).fare(1400).intervalMin(6)
                        .fareConfidence(TransitResDTO.FareConfidence.CONFIRMED).build());
        given(placeQueryService.getTaxiRoute(LAT_A, LNG_A, LAT_MID, LNG_A))
                .willReturn(Optional.of(new PlaceResDTO.TaxiRoute(4800, 0, 1500, 6)));
        given(placeQueryService.getWalkingRoute(LAT_A, LNG_A, LAT_MID, LNG_A))
                .willReturn(Optional.of(new PlaceResDTO.WalkingRoute(1400, 21)));

        TransitCandidateResDTO.Result result = service.calculate(PROJECT_ID, List.of(1L, 2L));

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
        verify(publicTransitQueryService).getCombinedRoute(LAT_A, LNG_A, LAT_MID, LNG_A);
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

        TransitCandidateResDTO.Result result = service.calculate(PROJECT_ID, List.of(1L, 2L));

        TransitCandidateResDTO.Segment segment = result.segments().get(0);
        assertThat(segment.defaultMode()).isEqualTo(TransitMode.CAR);
        assertThat(segment.candidates()).extracting(TransitCandidateResDTO.Candidate::mode)
                .containsExactly(TransitMode.CAR, TransitMode.TAXI, TransitMode.WALK);
        verify(publicTransitQueryService, never())
                .getCombinedRoute(anyDouble(), anyDouble(), anyDouble(), anyDouble());
    }

    @Test
    @DisplayName("근거리 임계 바로 위(301m)는 대중교통·택시까지 실제로 조회한다")
    void justOverNearThresholdCallsAllModes() {
        givenProject(TransportPref.PUBLIC);
        given(blockRepository.findAllByIdInAndProject_IdAndDeletedAtIsNull(List.of(1L, 2L), PROJECT_ID))
                .willReturn(List.of(blockAt(1L, LAT_A, LNG_A), blockAt(2L, LAT_JUST_OVER_NEAR, LNG_A)));
        given(publicTransitQueryService.getCombinedRoute(LAT_A, LNG_A, LAT_JUST_OVER_NEAR, LNG_A))
                .willReturn(TransitResDTO.Route.builder()
                        .durationMin(4).fare(1400).intervalMin(6)
                        .fareConfidence(TransitResDTO.FareConfidence.CONFIRMED).build());
        given(placeQueryService.getTaxiRoute(LAT_A, LNG_A, LAT_JUST_OVER_NEAR, LNG_A))
                .willReturn(Optional.of(new PlaceResDTO.TaxiRoute(4800, 0, 380, 2)));
        given(placeQueryService.getWalkingRoute(LAT_A, LNG_A, LAT_JUST_OVER_NEAR, LNG_A))
                .willReturn(Optional.of(new PlaceResDTO.WalkingRoute(360, 6)));

        TransitCandidateResDTO.Result result = service.calculate(PROJECT_ID, List.of(1L, 2L));

        assertThat(result.segments().get(0).candidates()).extracting(TransitCandidateResDTO.Candidate::mode)
                .containsExactly(TransitMode.TRANSIT, TransitMode.TAXI, TransitMode.WALK);
        verify(publicTransitQueryService).getCombinedRoute(LAT_A, LNG_A, LAT_JUST_OVER_NEAR, LNG_A);
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

        TransitCandidateResDTO.Result result = service.calculate(PROJECT_ID, List.of(1L, 2L));

        assertThat(result.segments().get(0).candidates()).extracting(TransitCandidateResDTO.Candidate::mode)
                .containsExactly(TransitMode.WALK);
        verify(publicTransitQueryService, never())
                .getCombinedRoute(anyDouble(), anyDouble(), anyDouble(), anyDouble());
        verify(placeQueryService, never())
                .getTaxiRoute(anyDouble(), anyDouble(), anyDouble(), anyDouble());
    }

    @Test
    @DisplayName("도보 임계 바로 위(2001m)는 도보를 호출하지 않는다")
    void overWalkThresholdSkipsWalkCall() {
        givenProject(TransportPref.PUBLIC);
        given(blockRepository.findAllByIdInAndProject_IdAndDeletedAtIsNull(List.of(1L, 2L), PROJECT_ID))
                .willReturn(List.of(blockAt(1L, LAT_A, LNG_A), blockAt(2L, LAT_JUST_OVER_WALK, LNG_A)));
        given(publicTransitQueryService.getCombinedRoute(LAT_A, LNG_A, LAT_JUST_OVER_WALK, LNG_A))
                .willReturn(TransitResDTO.Route.builder()
                        .durationMin(12).fare(1400).intervalMin(7)
                        .fareConfidence(TransitResDTO.FareConfidence.CONFIRMED).build());
        given(placeQueryService.getTaxiRoute(LAT_A, LNG_A, LAT_JUST_OVER_WALK, LNG_A))
                .willReturn(Optional.of(new PlaceResDTO.TaxiRoute(5800, 0, 2600, 9)));

        TransitCandidateResDTO.Result result = service.calculate(PROJECT_ID, List.of(1L, 2L));

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
        given(publicTransitQueryService.getCombinedRoute(LAT_A, LNG_A, LAT_JUST_UNDER_WALK, LNG_A))
                .willReturn(TransitResDTO.Route.builder()
                        .durationMin(12).fare(1400).intervalMin(7)
                        .fareConfidence(TransitResDTO.FareConfidence.CONFIRMED).build());
        given(placeQueryService.getTaxiRoute(LAT_A, LNG_A, LAT_JUST_UNDER_WALK, LNG_A))
                .willReturn(Optional.of(new PlaceResDTO.TaxiRoute(5800, 0, 2600, 9)));
        given(placeQueryService.getWalkingRoute(LAT_A, LNG_A, LAT_JUST_UNDER_WALK, LNG_A))
                .willReturn(Optional.of(new PlaceResDTO.WalkingRoute(2400, 34)));

        TransitCandidateResDTO.Result result = service.calculate(PROJECT_ID, List.of(1L, 2L));

        assertThat(result.segments().get(0).candidates()).extracting(TransitCandidateResDTO.Candidate::mode)
                .containsExactly(TransitMode.TRANSIT, TransitMode.TAXI, TransitMode.WALK);
        verify(placeQueryService).getWalkingRoute(LAT_A, LNG_A, LAT_JUST_UNDER_WALK, LNG_A);
    }
}
