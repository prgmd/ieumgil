package com.ssafy.ieumgil.domain.transit.service;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import com.ssafy.ieumgil.domain.transit.dto.TransitCandidateResDTO.CandidateStatus;
import com.ssafy.ieumgil.domain.transit.dto.TransitCandidateResDTO.TransitMode;
import com.ssafy.ieumgil.domain.transit.dto.TransitLegResDTO;
import com.ssafy.ieumgil.domain.transit.dto.TransitResDTO;
import com.ssafy.ieumgil.domain.transit.dto.TransitScheduleResDTO;
import com.ssafy.ieumgil.domain.transit.exception.TransitErrorCode;
import com.ssafy.ieumgil.domain.transit.exception.TransitException;
import com.ssafy.ieumgil.domain.transit.util.IntercityLegs;
import com.ssafy.ieumgil.global.exception.CustomException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.io.InputStream;
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
        return blockAt(id, lat, lng, dayNo, null);
    }

    /**
     * startTime을 지정하는 오버로드. 시외 구간의 기준 시각(base = startTime + durationMin)이
     * from 블록의 저장된 시각에서 나오므로, 시간표가 붙는 시외 구간을 테스트할 때는 이 오버로드로
     * from 블록의 startTime을 채워야 한다.
     */
    private Block blockAt(long id, double lat, double lng, int dayNo, LocalTime startTime) {
        return Block.builder()
                .id(id).dayNo(dayNo).orderKey("a" + id).name("블록" + id)
                .category(BlockCategory.SPOT).durationMin(60).budget(0)
                .lat(BigDecimal.valueOf(lat)).lng(BigDecimal.valueOf(lng))
                .startTime(startTime)
                .source(BlockSource.KAKAO)
                .build();
    }

    private void givenProject(TransportPref pref) {
        given(projectRepository.findByIdAndDeletedAtIsNull(PROJECT_ID))
                .willReturn(Optional.of(Project.builder().transportPref(pref).build()));
    }

    /** ODsay 경로 목록 응답 하나를 durationMin·fare·intervalMin·distanceM만으로 단순화해 만든다. */
    private OdsayRouteResponse.Path pathOf(int durationMin, Integer fare, Integer intervalMin, Integer distanceM) {
        return new OdsayRouteResponse.Path(1,
                new OdsayRouteResponse.Info(durationMin, fare, intervalMin, distanceM, null, null, null, null, null),
                List.of());
    }

    /**
     * 시외 후보의 접근·이탈을 0분·0원으로 단순화한다. 승하차 지점이 곧 블록 좌표와 같다고
     * 둬(access/egress 호출이 같은 좌표를 시작·끝으로 받는다) 대기시간·door-to-door 소요 계산에
     * 접근·이탈이 끼어들지 않게 한다 — 시간표 필터링(기준 시각 이후 편)을 정확히 검증하려는
     * 기존 테스트들이 접근 소요 때문에 편을 놓치지 않도록 한다.
     */
    private void givenZeroAccessEgress(
            double boardingLat, double boardingLng, double alightingLat, double alightingLng) {
        given(publicTransitQueryService.getCombinedRoutes(boardingLat, boardingLng, boardingLat, boardingLng))
                .willReturn(List.of(pathOf(0, 0, null, null)));
        given(publicTransitQueryService.getCombinedRoutes(alightingLat, alightingLng, alightingLat, alightingLng))
                .willReturn(List.of(pathOf(0, 0, null, null)));
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
                .extracting(TransitCandidateResDTO.Candidate::mode, TransitCandidateResDTO.Candidate::status)
                .containsExactly(
                        tuple(TransitMode.TRANSIT, CandidateStatus.LOOKUP_FAILED),
                        tuple(TransitMode.TAXI, CandidateStatus.OK));
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
                .allSatisfy(candidate -> assertThat(candidate.status()).isEqualTo(CandidateStatus.LOOKUP_FAILED));
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
        assertThat(car.status()).isEqualTo(CandidateStatus.OK);
    }

    @Test
    @DisplayName("경로 목록 조회 자체가 실패해도(paths 비어있음) 자차·택시는 지금처럼 그대로 만든다")
    void 경로_조회가_실패하면_자차_택시를_그대로_만든다() {
        givenProject(TransportPref.CAR);
        given(blockRepository.findAllByIdInAndProject_IdAndDeletedAtIsNull(List.of(1L, 3L), PROJECT_ID))
                .willReturn(List.of(cheongjuBlock(), jejuBlock()));
        // ODsay 대중교통 경로 조회 자체가 실패해도 자차·택시 후보 판단에는 영향이 없다
        given(publicTransitQueryService.getCombinedRoutes(anyDouble(), anyDouble(), anyDouble(), anyDouble()))
                .willThrow(new TransitException(TransitErrorCode.ROUTE_NOT_FOUND));
        given(placeQueryService.getTaxiRoute(anyDouble(), anyDouble(), anyDouble(), anyDouble()))
                .willReturn(Optional.of(new PlaceResDTO.TaxiRoute(556600, 9900, 436642, 356)));

        TransitCandidateResDTO.Result result = service.calculate(PROJECT_ID, List.of(1L, 3L), null);

        TransitCandidateResDTO.Candidate taxi = candidateOf(result, TransitMode.TAXI);
        assertThat(taxi.status()).isEqualTo(CandidateStatus.OK);
        assertThat(taxi.fareConfidence()).isEqualTo(TransitResDTO.FareConfidence.CONFIRMED);
        TransitCandidateResDTO.Candidate car = candidateOf(result, TransitMode.CAR);
        assertThat(car.status()).isEqualTo(CandidateStatus.OK);
    }

    @Test
    @DisplayName("드라이빙 조회만 실패해 status=LOOKUP_FAILED인 후보는 육로 판정과 무관하게 그대로 남는다")
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
        assertThat(taxi.status()).isEqualTo(CandidateStatus.LOOKUP_FAILED);
        assertThat(taxi.caution()).isNull();
    }

    @Test
    @DisplayName("시외 구간에서 드라이빙 조회가 실패해도 항공이 기본 후보가 되고 기준 시각 누적에 실제 소요시간을 쓴다")
    void 드라이빙_조회가_실패한_시외_구간은_항공이_기본이_된다() {
        given(blockRepository.findAllByIdInAndProject_IdAndDeletedAtIsNull(List.of(2L, 3L), PROJECT_ID))
                .willReturn(List.of(busanBlock(), jejuBlock()));
        given(projectRepository.findByIdAndDeletedAtIsNull(PROJECT_ID)).willReturn(Optional.of(publicProject()));
        given(publicTransitQueryService.getCombinedRoutes(anyDouble(), anyDouble(), anyDouble(), anyDouble()))
                .willReturn(List.of(airPath()));
        givenZeroAccessEgress(LAT_BUSAN, LNG_BUSAN, LAT_JEJU, LNG_JEJU);
        // 카카오 길찾기 자체가 실패한다 — driving==null이 택시 후보를 status=LOOKUP_FAILED로 남기는
        // 유일한 이유다(roadUnreachable 삭제 후에도 이 경로는 그대로 살아 있어야 한다).
        given(placeQueryService.getTaxiRoute(anyDouble(), anyDouble(), anyDouble(), anyDouble()))
                .willReturn(Optional.empty());
        given(transitScheduleQueryService.getFlightSchedule(anyInt(), anyInt(), any(LocalDate.class)))
                .willReturn(List.of(TransitScheduleResDTO.FlightSchedule.builder()
                        .airline("대한항공").flightNo("KE1801")
                        .departureTime("10:30").arrivalTime("11:35").runDay("매일")
                        .build()));

        TransitCandidateResDTO.Result result = service.calculate(PROJECT_ID, List.of(2L, 3L), null);

        TransitCandidateResDTO.Segment segment = result.segments().get(0);
        // 택시는 후보에서 사라지지 않는다 — driving==null이라 status=LOOKUP_FAILED로 남을 뿐이다
        TransitCandidateResDTO.Candidate taxi = candidateOf(result, TransitMode.TAXI);
        assertThat(taxi.status()).isEqualTo(CandidateStatus.LOOKUP_FAILED);
        assertThat(segment.candidates()).extracting(Candidate::mode).contains(TransitMode.AIR);
        assertThat(segment.defaultMode()).isEqualTo(TransitMode.AIR);
        // door-to-door 소요다: 접근·이탈은 0분이지만 대기(09:00 종료+공항 여유 40분 기준 10:30발 =
        // 90분 대기)가 실제 항공편 소요(65분)에 더해진다(90+65=155)
        assertThat(candidateOf(result, TransitMode.AIR).durationMin()).isEqualTo(155);
    }

    @Test
    @DisplayName("[실측 35/35] 시외 구간은 subPath 역 ID로 수단별로 나뉘고 기준 시각 이후 편이 붙는다")
    void 시외_구간은_수단별로_나뉜다() {
        given(blockRepository.findAllByIdInAndProject_IdAndDeletedAtIsNull(List.of(1L, 2L), PROJECT_ID))
                .willReturn(List.of(seoulBlock(), busanBlock()));
        given(projectRepository.findByIdAndDeletedAtIsNull(PROJECT_ID)).willReturn(Optional.of(publicProject()));
        given(publicTransitQueryService.getCombinedRoutes(anyDouble(), anyDouble(), anyDouble(), anyDouble()))
                .willReturn(List.of(trainPath()));  // pathType=11
        givenZeroAccessEgress(LAT_SEOUL, LNG_SEOUL, LAT_BUSAN, LNG_BUSAN);
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
        // durationMin·fare는 이제 door-to-door다. 접근·이탈은 0분·0원으로 단순화했으니
        // durationMin은 대기(14:10 기준 16:00발 = 120분)+열차 소요(157분)=277분이고,
        // fare는 접근(0)+시외 totalPayment(59800)+이탈(0)=59800이다
        assertThat(train.durationMin()).isEqualTo(277);
        assertThat(train.fare()).isEqualTo(59800);
        assertThat(train.fareConfidence()).isEqualTo(TransitResDTO.FareConfidence.CONFIRMED);
        assertThat(train.accessMin()).isEqualTo(0);
        assertThat(train.egressMin()).isEqualTo(0);
        assertThat(train.referenceAt()).isEqualTo("14:10");
        // ID가 그대로 들어간 것이 위 eq(3300128), eq(3300108) 스텁 매칭으로 이미 증명됐다 —
        // 이름 검색을 쓰지 않았다는 것도 함께 못 박는다
        verify(transitScheduleQueryService, never()).searchTrainStation(anyString());
    }

    @Test
    @DisplayName("환승 시외 구간(pathType 20)은 두 번째 leg 시간표를 붙여 연결편을 계산한다")
    void 환승_시외_구간은_연결편을_계산한다() {
        // 이 테스트가 실패해야 하는 회귀: candidateFor가 legs.legs().get(0) 대신
        // legs.legs().get(size-1)을 써도 기존 단일 leg 픽스처들은 전부 통과한다(leg가 하나뿐이라
        // get(0)==get(size-1)). 첫 leg(기차·3300xxx)와 두 번째 leg(항공·3500xxx)가 서로 다른
        // 역 ID 대역·시간표 API를 쓰는 이 픽스처만이 그 스왑을 실제로 잡아낸다.
        given(blockRepository.findAllByIdInAndProject_IdAndDeletedAtIsNull(List.of(1L, 3L), PROJECT_ID))
                .willReturn(List.of(seoulBlock(), jejuBlock()));
        given(projectRepository.findByIdAndDeletedAtIsNull(PROJECT_ID)).willReturn(Optional.of(publicProject()));
        given(publicTransitQueryService.getCombinedRoutes(anyDouble(), anyDouble(), anyDouble(), anyDouble()))
                .willReturn(List.of(transferTrainAirPath()));
        givenZeroAccessEgress(LAT_SEOUL, LNG_SEOUL, LAT_JEJU, LNG_JEJU);
        // 첫 leg에 편을 둘 둔다 — 두 번째 leg 시간표 조회가 첫 편마다 다시 불리는 N×M 회귀라면
        // 아래 verify(times(1))가 잡아낸다(편이 하나뿐이면 한 번 호출과 구분되지 않는다).
        given(transitScheduleQueryService.getTrainSchedule(eq(3300128), eq(3300140), any(LocalDate.class)))
                .willReturn(List.of(
                        train("KTX", 1, "16:00", "18:37", 59800),
                        train("무궁화", 501, "17:00", "19:30", 28600)));
        // 19:00발은 18:37 도착 + 10분(기차 여유)과 40분(항공 여유) 사이에 있다 — AIR 여유를 쓰면
        // 19:17 이전이라 제외되고 20:00발이 남지만, secondMode 대신 첫 leg의 mode(TRAIN, 10분)를
        // 잘못 쓰면 18:47 이후라 통과해 19:00발이 선택된다. 두 여유를 구분하는 유일한 편이다.
        given(transitScheduleQueryService.getFlightSchedule(eq(3500008), eq(3500003), any(LocalDate.class)))
                .willReturn(List.of(
                        TransitScheduleResDTO.FlightSchedule.builder()
                                .airline("진에어").flightNo("7C150")
                                .departureTime("19:00").arrivalTime("19:50").runDay("매일")
                                .build(),
                        TransitScheduleResDTO.FlightSchedule.builder()
                                .airline("제주항공").flightNo("7C200")
                                .departureTime("20:00").arrivalTime("21:00").runDay("매일")
                                .build()));

        TransitCandidateResDTO.Result result =
                service.calculate(PROJECT_ID, List.of(1L, 3L), null);

        Candidate train = candidateOf(result, TransitMode.TRAIN);
        assertThat(train.transferCount()).isEqualTo(1);
        // 17:00발(19:30 도착)은 20:10 이후 편이 없어 연결편을 못 찾고 빠진다 — 16:00발만 남는다
        assertThat(train.departures()).hasSize(1);
        TransitCandidateResDTO.Departure first = train.departures().get(0);
        assertThat(first.departureAt()).isEqualTo("16:00");
        TransitCandidateResDTO.Connection connection = first.connection();
        assertThat(connection).isNotNull();
        assertThat(connection.name()).isEqualTo("제주항공 7C200");
        // 18:37 도착 + 40분(항공 탑승 여유) = 19:17 이후 최속편 → 19:00발은 이르므로 빠지고 20:00
        assertThat(connection.departureAt()).isEqualTo("20:00");
        assertThat(connection.arrivalAt()).isEqualTo("21:00");
        // 환승 지점 이름은 두 번째 leg(항공)의 SubPath에서 온다 — 픽스처 전체에서 "광주공항"은
        // 이 leg의 startName뿐이라 다른 leg와 뒤바뀌거나 필드가 스왑되면 이 값이 깨진다
        assertThat(connection.fromStation()).isEqualTo("광주공항");
        assertThat(connection.toStation()).isEqualTo("제주공항");
        // transferMin은 실제 대기(18:37→20:00)다: 83분
        assertThat(connection.transferMin()).isEqualTo(83);
        // door-to-door: 접근·이탈은 0분이지만 대기(14:10 기준 16:00발=120분)+기차(157분)+
        // 환승대기(83분)+항공(60분)=420분이다. fare는 접근(0)+시외 totalPayment(119800)+이탈(0)
        assertThat(train.durationMin()).isEqualTo(420);
        assertThat(train.fare()).isEqualTo(119800);
        assertThat(train.fareConfidence()).isEqualTo(TransitResDTO.FareConfidence.CONFIRMED);
        // 두 번째 leg 시간표는 첫 leg 편이 둘이어도 한 번만 조회된다
        verify(transitScheduleQueryService, times(1))
                .getFlightSchedule(eq(3500008), eq(3500003), any(LocalDate.class));
    }

    @Test
    @DisplayName("[실측 35/35] 시외버스(tt6) leg도 고속버스(tt5)와 같은 searchInterBusSchedule로 간다")
    void 시외버스_leg도_고속버스와_같은_엔드포인트로_간다() {
        given(blockRepository.findAllByIdInAndProject_IdAndDeletedAtIsNull(List.of(1L, 2L), PROJECT_ID))
                .willReturn(List.of(seoulBlock(), busanBlock()));
        given(projectRepository.findByIdAndDeletedAtIsNull(PROJECT_ID)).willReturn(Optional.of(publicProject()));
        given(publicTransitQueryService.getCombinedRoutes(anyDouble(), anyDouble(), anyDouble(), anyDouble()))
                .willReturn(List.of(interCityBusPath()));  // trafficType=6(시외버스), 대역 3600→4000
        givenZeroAccessEgress(LAT_SEOUL, LNG_SEOUL, LAT_BUSAN, LNG_BUSAN);
        given(transitScheduleQueryService.getIntercityBusSchedule(eq(3600210), eq(4000135), any(LocalDate.class)))
                .willReturn(List.of(bus(2, "16:00", 140, 20900)));

        TransitCandidateResDTO.Result result =
                service.calculate(PROJECT_ID, List.of(1L, 2L), LocalTime.of(9, 0));

        Candidate expressBus = candidateOf(result, TransitMode.EXPRESS_BUS);
        assertThat(expressBus.status()).isEqualTo(CandidateStatus.OK);
        assertThat(expressBus.departures()).isNotEmpty();
        assertThat(expressBus.departures().get(0).fare()).isEqualTo(20900);
        // 시외버스(tt6)를 위한 별도 엔드포인트로 가지 않는다 — 이름 검색도 쓰지 않는다
        verify(transitScheduleQueryService, never()).searchExpressBusTerminal(anyString());
        verify(transitScheduleQueryService, never()).searchIntercityBusTerminal(anyString());
    }

    @Test
    @DisplayName("[실측 19/19] 항공 leg의 startID·endID가 이름 검색 없이 airServiceTime에 그대로 전달된다")
    void 항공_leg_역_ID가_그대로_시간표_API에_전달된다() {
        // 기준 시각(부산 블록 종료 08:00+60분=09:00 + 항공 여유)보다 늦은 편이어야 남는다
        given(blockRepository.findAllByIdInAndProject_IdAndDeletedAtIsNull(List.of(2L, 3L), PROJECT_ID))
                .willReturn(List.of(busanBlock(), jejuBlock()));
        given(projectRepository.findByIdAndDeletedAtIsNull(PROJECT_ID)).willReturn(Optional.of(publicProject()));
        given(publicTransitQueryService.getCombinedRoutes(anyDouble(), anyDouble(), anyDouble(), anyDouble()))
                .willReturn(List.of(airPath()));  // startID=3500005(청주공항)·endID=3500003(제주공항)
        givenZeroAccessEgress(LAT_BUSAN, LNG_BUSAN, LAT_JEJU, LNG_JEJU);
        given(transitScheduleQueryService.getFlightSchedule(eq(3500005), eq(3500003), any(LocalDate.class)))
                .willReturn(List.of(TransitScheduleResDTO.FlightSchedule.builder()
                        .airline("제주항공").flightNo("7C101")
                        .departureTime("10:30").arrivalTime("11:35").runDay("매일")
                        .build()));

        TransitCandidateResDTO.Result result =
                service.calculate(PROJECT_ID, List.of(2L, 3L), LocalTime.of(9, 0));

        Candidate air = candidateOf(result, TransitMode.AIR);
        assertThat(air.status()).isEqualTo(CandidateStatus.OK);
        assertThat(air.departures()).hasSize(1);
        assertThat(air.departures().get(0).name()).isEqualTo("제주항공 7C101");
    }

    @Test
    @DisplayName("기준 시각보다 이른 편은 후보에서 뺀다")
    void 기준_시각_이전_편은_제외한다() {
        given(blockRepository.findAllByIdInAndProject_IdAndDeletedAtIsNull(List.of(1L, 2L), PROJECT_ID))
                .willReturn(List.of(seoulBlock(), busanBlock()));
        given(projectRepository.findByIdAndDeletedAtIsNull(PROJECT_ID)).willReturn(Optional.of(publicProject()));
        given(publicTransitQueryService.getCombinedRoutes(anyDouble(), anyDouble(), anyDouble(), anyDouble()))
                .willReturn(List.of(trainPath()));
        givenZeroAccessEgress(LAT_SEOUL, LNG_SEOUL, LAT_BUSAN, LNG_BUSAN);
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
    @DisplayName("블록 사이 공백이 기준 시각에 반영된다 — 누적 모델의 버그")
    void 공백이_기준_시각에_반영된다() {
        // 10:00~11:00 관람 블록(1) 다음에 3시간 공백을 두고 14:00에 시작하는 블록(2)이 온다
        // (durationMin=0 — 도착과 동시에 출발해야 하는 지점). 시외 구간(2->3)의 기준은 블록2의
        // 저장된 종료 시각(14:00)이어야 한다. 앞 구간에서 이동시간만 누적하던 옛 모델은 이 공백을 보지 못하고
        // dayStart(09:00 기본값)에서 이동시간만 더해 훨씬 이른 시각(09:45)을 기준으로 삼았다 —
        // 그래서 실제로는 탈 수 없는 10:00발 열차까지 후보에 남겼다.
        Block block1 = blockAt(1L, LAT_A, LNG_A, 1, LocalTime.of(10, 0));
        Block block2 = Block.builder()
                .id(2L).dayNo(1).orderKey("a2").name("블록2")
                .category(BlockCategory.SPOT).durationMin(0).budget(0)
                .lat(BigDecimal.valueOf(LAT_A)).lng(BigDecimal.valueOf(LNG_A))
                .startTime(LocalTime.of(14, 0))
                .source(BlockSource.KAKAO)
                .build();
        Block block3 = blockAt(3L, LAT_BUSAN, LNG_BUSAN, 1);
        given(blockRepository.findAllByIdInAndProject_IdAndDeletedAtIsNull(List.of(1L, 2L, 3L), PROJECT_ID))
                .willReturn(List.of(block1, block2, block3));
        given(projectRepository.findByIdAndDeletedAtIsNull(PROJECT_ID)).willReturn(Optional.of(publicProject()));
        given(publicTransitQueryService.getCombinedRoutes(anyDouble(), anyDouble(), anyDouble(), anyDouble()))
                .willReturn(List.of(trainPath()));
        // trainPath()의 승차 지점은 LAT_SEOUL·LNG_SEOUL이다 — 블록2는 LAT_A·LNG_A라 접근 호출이
        // 서로 다른 좌표쌍(비-대칭)이다. 하차 지점은 블록3(LAT_BUSAN·LNG_BUSAN)과 같아 이탈은 0분이면 된다.
        given(publicTransitQueryService.getCombinedRoutes(LAT_A, LNG_A, LAT_SEOUL, LNG_SEOUL))
                .willReturn(List.of(pathOf(0, 0, null, null)));
        given(publicTransitQueryService.getCombinedRoutes(LAT_BUSAN, LNG_BUSAN, LAT_BUSAN, LNG_BUSAN))
                .willReturn(List.of(pathOf(0, 0, null, null)));
        given(transitScheduleQueryService.getTrainSchedule(anyInt(), anyInt(), any(LocalDate.class)))
                .willReturn(List.of(
                        train("KTX", 1, "10:00", "12:37", 59800),
                        train("KTX", 99, "15:00", "17:37", 59800)));

        TransitCandidateResDTO.Result result =
                service.calculate(PROJECT_ID, List.of(1L, 2L, 3L), null);

        // 기준은 블록2의 종료 시각(14:00) + 기차 탑승 여유(10분) = 14:10이다. 10:00발은 그 이전이라
        // 빠지고 15:00발만 남아야 한다.
        Candidate train = result.segments().get(1).candidates().stream()
                .filter(c -> c.mode() == TransitMode.TRAIN).findFirst().orElseThrow();
        assertThat(train.departures()).extracting(d -> d.departureAt()).containsExactly("15:00");
    }

    @Test
    @DisplayName("한 Day에 시외 구간이 둘이어도 둘 다 시간표가 붙는다")
    void 시외_구간_둘_다_시간표가_붙는다() {
        given(blockRepository.findAllByIdInAndProject_IdAndDeletedAtIsNull(List.of(1L, 2L, 3L), PROJECT_ID))
                .willReturn(List.of(seoulBlock(), busanBlock(), jejuBlock()));
        given(projectRepository.findByIdAndDeletedAtIsNull(PROJECT_ID)).willReturn(Optional.of(publicProject()));
        given(publicTransitQueryService.getCombinedRoutes(anyDouble(), anyDouble(), anyDouble(), anyDouble()))
                .willReturn(List.of(trainPath()));
        given(transitScheduleQueryService.getTrainSchedule(anyInt(), anyInt(), any(LocalDate.class)))
                .willReturn(List.of(train("KTX", 1, "16:00", "18:37", 59800)));

        TransitCandidateResDTO.Result result =
                service.calculate(PROJECT_ID, List.of(1L, 2L, 3L), null);

        // 고쳐지기 전에는 두 번째 구간이 timetableApplied=false·
        // skipReason="앞선 시외 구간의 편이 확정되지 않았습니다"였다 — 각 구간이 앞 구간의 확정과
        // 무관하게 자기 from 블록의 저장된 시각으로만 기준을 만드는 지금은 그 restriction이 사라졌다.
        assertThat(result.segments()).hasSize(2);
        assertThat(result.segments()).allSatisfy(
                segment -> assertThat(segment.timetableApplied()).isTrue());
        // Day 하나에 시외 구간이 둘이면 시간표도 구간마다 한 번씩, 총 두 번 조회한다
        verify(transitScheduleQueryService, times(2))
                .getTrainSchedule(anyInt(), anyInt(), any(LocalDate.class));
    }

    @Test
    @DisplayName("from 블록에 시작 시각이 없으면 앞 구간 값으로 대체하지 않고 시간표를 건너뛴다")
    void 시작_시각이_없으면_앞_구간으로_대체하지_않는다() {
        // 서울(1, 시각 있음)->부산(2, 시각 없음)->제주(3). 두 번째 구간(부산->제주)의 from인
        // 부산 블록에 시각이 없으므로, 앞 구간(서울->부산)이 정상적으로 만든 기준으로 대체되면
        // 안 된다 — 그게 바로 공백을 무시하던 누적 모델의 버그였다.
        Block busanNoStartTime = blockAt(2L, LAT_BUSAN, LNG_BUSAN);
        given(blockRepository.findAllByIdInAndProject_IdAndDeletedAtIsNull(List.of(1L, 2L, 3L), PROJECT_ID))
                .willReturn(List.of(seoulBlock(), busanNoStartTime, jejuBlock()));
        given(projectRepository.findByIdAndDeletedAtIsNull(PROJECT_ID)).willReturn(Optional.of(publicProject()));
        given(publicTransitQueryService.getCombinedRoutes(anyDouble(), anyDouble(), anyDouble(), anyDouble()))
                .willReturn(List.of(trainPath()));
        given(transitScheduleQueryService.getTrainSchedule(anyInt(), anyInt(), any(LocalDate.class)))
                .willReturn(List.of(train("KTX", 1, "16:00", "18:37", 59800)));

        TransitCandidateResDTO.Result result =
                service.calculate(PROJECT_ID, List.of(1L, 2L, 3L), null);

        assertThat(result.segments()).hasSize(2);
        assertThat(result.segments().get(0).timetableApplied()).isTrue();  // 서울->부산: 정상
        TransitCandidateResDTO.Segment segment1 = result.segments().get(1);  // 부산->제주
        assertThat(segment1.timetableApplied()).isFalse();
        assertThat(segment1.timetableSkipReason())
                .isEqualTo("출발 블록에 시작 시각이 없어 기준 시각을 계산할 수 없습니다");
        // 앞 구간의 기준으로 대체됐다면 두 번째 구간도 조회가 일어났을 것이다 — 한 번만
        // 조회됐다는 것이 대체하지 않았다는 증거다
        verify(transitScheduleQueryService, times(1))
                .getTrainSchedule(anyInt(), anyInt(), any(LocalDate.class));
        Candidate train = segment1.candidates().stream()
                .filter(c -> c.mode() == TransitMode.TRAIN).findFirst().orElseThrow();
        assertThat(train.departures()).isEmpty();
        // 시간표는 못 붙였지만 ODsay가 이 수단(기차)의 경로 자체는 줬다 — 그 경로 자신의
        // 시각·leg은 채운다(브리프 상태 분기 3: "시간표 미적용 → OK, ODsay 시외 leg 시간 사용")
        assertThat(train.legs()).hasSize(1);
        assertThat(train.durationMin()).isEqualTo(157);
    }

    @Test
    @DisplayName("from 블록의 저장 시각이 자정을 넘기면(base>=1440) 다음 날 날짜로 시간표를 조회한다")
    void base가_자정을_넘기면_다음_날_날짜로_조회한다() {
        // 23:50 시작 + 90분 체류 = base 1520분(24:20) — from 블록 자체의 저장 시각만으로도
        // 자정을 넘긴다(수단 여유를 더하기 전에 이미 그렇다).
        Block lateBlock = Block.builder()
                .id(1L).dayNo(1).orderKey("a1").name("블록1")
                .category(BlockCategory.SPOT).durationMin(90).budget(0)
                .lat(BigDecimal.valueOf(LAT_SEOUL)).lng(BigDecimal.valueOf(LNG_SEOUL))
                .startTime(LocalTime.of(23, 50))
                .source(BlockSource.KAKAO)
                .build();
        given(blockRepository.findAllByIdInAndProject_IdAndDeletedAtIsNull(List.of(1L, 2L), PROJECT_ID))
                .willReturn(List.of(lateBlock, busanBlock()));
        given(projectRepository.findByIdAndDeletedAtIsNull(PROJECT_ID)).willReturn(Optional.of(publicProject()));
        given(publicTransitQueryService.getCombinedRoutes(anyDouble(), anyDouble(), anyDouble(), anyDouble()))
                .willReturn(List.of(trainPath()));
        given(transitScheduleQueryService.getTrainSchedule(anyInt(), anyInt(), any(LocalDate.class)))
                .willReturn(List.of());

        service.calculate(PROJECT_ID, List.of(1L, 2L), null);

        // publicProject().startDate == 2026-08-10. base가 자정을 하루 넘겼으므로 조회 날짜는
        // 08-11이어야 한다.
        verify(transitScheduleQueryService)
                .getTrainSchedule(anyInt(), anyInt(), eq(LocalDate.of(2026, 8, 11)));
    }

    @Test
    @DisplayName("base 자체는 자정 전이어도 수단 여유를 더하면 자정을 넘길 수 있다 — 그때도 날짜가 함께 넘어가야 한다")
    void 기준_시각이_수단_여유로_자정을_넘기면_조회_날짜도_같이_넘어간다() {
        // from 블록이 23:50에 끝난다(base=1430, 그 자체는 자정 전). 항공 여유(40분)를 더한
        // 절대 기준(1470)은 자정을 넘겨 다음 날 00:30이다 — 시각만 다음 날로 넘기고 조회 날짜는
        // base 하나만 보고 오늘로 두면(고쳐지기 전 버그), 오늘 이미 지나간 항공편까지
        // "기준 이후"로 통과해 버린다(시각 필터가 00:xx 기준이라 그날 편이면 전부 통과한다).
        Block lateBlock = blockAt(1L, LAT_CHEONGJU, LNG_CHEONGJU, 1, LocalTime.of(22, 50));
        given(blockRepository.findAllByIdInAndProject_IdAndDeletedAtIsNull(List.of(1L, 3L), PROJECT_ID))
                .willReturn(List.of(lateBlock, jejuBlock()));
        given(projectRepository.findByIdAndDeletedAtIsNull(PROJECT_ID)).willReturn(Optional.of(publicProject()));
        given(publicTransitQueryService.getCombinedRoutes(anyDouble(), anyDouble(), anyDouble(), anyDouble()))
                .willReturn(List.of(airPath()));
        // airPath()의 승차 지점은 LAT_BUSAN·LNG_BUSAN이라 접근이 비대칭 좌표쌍이다(청주→부산).
        // 접근을 0분으로 두지 않으면 airPath() 자신의 totalTime(356)이 재사용돼 base가 그 자체로
        // 자정을 넘겨 버려 — 이 테스트가 지키려는 "여유(margin)가 자정을 넘긴다" 전제가 깨진다.
        given(publicTransitQueryService.getCombinedRoutes(LAT_CHEONGJU, LNG_CHEONGJU, LAT_BUSAN, LNG_BUSAN))
                .willReturn(List.of(pathOf(0, 0, null, null)));
        given(publicTransitQueryService.getCombinedRoutes(LAT_JEJU, LNG_JEJU, LAT_JEJU, LNG_JEJU))
                .willReturn(List.of(pathOf(0, 0, null, null)));
        given(transitScheduleQueryService.getFlightSchedule(anyInt(), anyInt(), any(LocalDate.class)))
                .willReturn(List.of(TransitScheduleResDTO.FlightSchedule.builder()
                        .airline("대한항공").flightNo("KE1801")
                        .departureTime("08:00").arrivalTime("09:05").runDay("매일")
                        .build()));

        service.calculate(PROJECT_ID, List.of(1L, 3L), null);

        // publicProject().startDate == 2026-08-10. base(1430)+항공 여유(40)=1470이 자정을
        // 넘기므로 조회 날짜는 08-11이어야 한다 — base만 보고 08-10으로 조회하면(고쳐지기 전)
        // 이 verify가 실패한다.
        verify(transitScheduleQueryService)
                .getFlightSchedule(anyInt(), anyInt(), eq(LocalDate.of(2026, 8, 11)));
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
                        blockAt(1L, LAT_SEOUL, LNG_SEOUL, 1, LocalTime.of(13, 0)),
                        blockAt(2L, LAT_BUSAN, LNG_BUSAN, 2, LocalTime.of(8, 0)),
                        blockAt(3L, LAT_JEJU, LNG_JEJU, 2)));
        given(projectRepository.findByIdAndDeletedAtIsNull(PROJECT_ID)).willReturn(Optional.of(publicProject()));
        given(publicTransitQueryService.getCombinedRoutes(anyDouble(), anyDouble(), anyDouble(), anyDouble()))
                .willReturn(List.of(trainPath()));
        given(transitScheduleQueryService.getTrainSchedule(anyInt(), anyInt(), any(LocalDate.class)))
                .willReturn(List.of(train("KTX", 1, "16:00", "18:37", 59800)));

        TransitCandidateResDTO.Result result =
                service.calculate(PROJECT_ID, List.of(1L, 2L, 2L, 3L), LocalTime.of(9, 0));

        assertThat(result.segments()).hasSize(2);
        TransitCandidateResDTO.Segment day1Segment = result.segments().get(0);
        TransitCandidateResDTO.Segment day2Segment = result.segments().get(1);

        assertThat(day1Segment.timetableApplied()).isTrue();

        // 고쳐지기 전에는 day2Segment가 timetableApplied=false·
        // skipReason="앞선 시외 구간의 편이 확정되지 않았습니다"였다.
        assertThat(day2Segment.timetableApplied()).isTrue();
        assertThat(day2Segment.timetableSkipReason()).isNull();
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
                            blockAt(1L, LAT_SEOUL, LNG_SEOUL, 1, LocalTime.of(13, 0)),
                            blockAt(2L, LAT_BUSAN, LNG_BUSAN, 2, LocalTime.of(8, 0)),
                            blockAt(3L, LAT_JEJU, LNG_JEJU, 2)));
            given(projectRepository.findByIdAndDeletedAtIsNull(PROJECT_ID)).willReturn(Optional.of(publicProject()));
            given(publicTransitQueryService.getCombinedRoutes(anyDouble(), anyDouble(), anyDouble(), anyDouble()))
                    .willReturn(List.of(trainPath()));
            givenZeroAccessEgress(LAT_SEOUL, LNG_SEOUL, LAT_BUSAN, LNG_BUSAN);
            // Day1은 350ms 걸려도 자기 예산(600ms, 새 예산) 안에 끝나 정상 응답한다. 그 350ms를
            // 쓰고 나면 공유 예산에는 약 250ms만 남는다. Day2는 450ms가 걸리는데, 이건 "남은
            // ~250ms"로는 못 끝내지만(→ 취소되어 LOOKUP_FAILED) "새 600ms"라면 넉넉히 끝난다
            // (→ OK) — 그래서 이 둘의 결과가 갈리는 것 자체가 예산이 Day끼리 공유되는지
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

            assertThat(day1Train.status()).as("Day1은 자기 예산(600ms) 안에 끝난다").isEqualTo(CandidateStatus.OK);
            assertThat(day1Train.departures()).isNotEmpty();
            // 고쳐지기 전(Day마다 새 20초)이었다면 450ms < 600ms라 Day2도 status=OK였다.
            // 남은 예산(~250ms)을 받는 지금은 450ms가 그 예산을 넘겨 취소된다.
            assertThat(day2Train.status())
                    .as("Day2는 Day1이 쓰고 남은 예산만 받아 시간 안에 못 끝나고 취소된다")
                    .isEqualTo(CandidateStatus.LOOKUP_FAILED);
        } finally {
            TransitCandidateServiceImpl.TIMETABLE_TIMEOUT = original;
        }
    }

    @Test
    @DisplayName("역 ID가 알려진 대역이 아니면 추측하지 않고 그 수단만 빠진다 — 이름 검색으로 대체하지 않는다")
    void 대역을_판별할_수_없는_역_ID는_그_수단만_뺀다() {
        given(blockRepository.findAllByIdInAndProject_IdAndDeletedAtIsNull(List.of(1L, 2L), PROJECT_ID))
                .willReturn(List.of(seoulBlock(), busanBlock()));
        given(projectRepository.findByIdAndDeletedAtIsNull(PROJECT_ID)).willReturn(Optional.of(publicProject()));
        given(publicTransitQueryService.getCombinedRoutes(anyDouble(), anyDouble(), anyDouble(), anyDouble()))
                .willReturn(List.of(unknownBandTrainPath()));
        given(placeQueryService.getTaxiRoute(anyDouble(), anyDouble(), anyDouble(), anyDouble()))
                .willReturn(Optional.of(new PlaceResDTO.TaxiRoute(374600, 0, 401839, 310)));

        TransitCandidateResDTO.Result result =
                service.calculate(PROJECT_ID, List.of(1L, 2L), LocalTime.of(9, 0));

        Candidate train = candidateOf(result, TransitMode.TRAIN);
        assertThat(train.status()).isEqualTo(CandidateStatus.LOOKUP_FAILED);
        assertThat(candidateOf(result, TransitMode.TAXI).status()).isEqualTo(CandidateStatus.OK);
        // 대역을 못 판별했다고 이름 검색으로 대체하지 않는다 — 시간표 API 자체를 부르지 않는다
        verifyNoInteractions(transitScheduleQueryService);
    }

    @Test
    @DisplayName("접근 경로 조회가 실패하면 그 수단은 후보 목록에서 완전히 빠진다 — accessMin을 0으로 지어내지 않는다")
    void 접근_경로_조회가_실패하면_그_수단_후보를_만들지_않는다() {
        given(blockRepository.findAllByIdInAndProject_IdAndDeletedAtIsNull(List.of(1L, 2L), PROJECT_ID))
                .willReturn(List.of(seoulBlock(), busanBlock()));
        given(projectRepository.findByIdAndDeletedAtIsNull(PROJECT_ID)).willReturn(Optional.of(publicProject()));
        given(publicTransitQueryService.getCombinedRoutes(anyDouble(), anyDouble(), anyDouble(), anyDouble()))
                .willReturn(List.of(trainPath()));
        // trainPath()의 승차 지점은 LAT_SEOUL·LNG_SEOUL이다 — 접근 조회 자체가 실패한다
        given(publicTransitQueryService.getCombinedRoutes(LAT_SEOUL, LNG_SEOUL, LAT_SEOUL, LNG_SEOUL))
                .willThrow(new TransitException(TransitErrorCode.ROUTE_NOT_FOUND));

        TransitCandidateResDTO.Result result = service.calculate(PROJECT_ID, List.of(1L, 2L), null);

        // accessLegsOf가 empty를 반환하면 candidateFor는 null을 반환하고, 그 null은 후보
        // 목록에서 걸러진다 — LOOKUP_FAILED로도, accessMin=0인 OK로도 남지 않는다
        assertThat(result.segments().get(0).candidates())
                .extracting(Candidate::mode)
                .doesNotContain(TransitMode.TRAIN);
        // 접근이 실패했으므로 시간표 조회 자체를 시도하지 않는다
        verify(transitScheduleQueryService, never())
                .getTrainSchedule(anyInt(), anyInt(), any(LocalDate.class));
    }

    @Test
    @DisplayName("기준 시각 이후 편이 없으면 status=NO_SERVICE에 빈 departures다")
    void 남은_편이_없으면_NO_SERVICE다() {
        given(blockRepository.findAllByIdInAndProject_IdAndDeletedAtIsNull(List.of(1L, 2L), PROJECT_ID))
                .willReturn(List.of(seoulBlock(), busanBlock()));
        given(projectRepository.findByIdAndDeletedAtIsNull(PROJECT_ID)).willReturn(Optional.of(publicProject()));
        given(publicTransitQueryService.getCombinedRoutes(anyDouble(), anyDouble(), anyDouble(), anyDouble()))
                .willReturn(List.of(trainPath()));
        givenZeroAccessEgress(LAT_SEOUL, LNG_SEOUL, LAT_BUSAN, LNG_BUSAN);
        given(transitScheduleQueryService.getTrainSchedule(anyInt(), anyInt(), any(LocalDate.class)))
                .willReturn(List.of(train("KTX", 1, "05:13", "07:50", 59800)));

        TransitCandidateResDTO.Result result =
                service.calculate(PROJECT_ID, List.of(1L, 2L), LocalTime.of(22, 0));

        Candidate train = candidateOf(result, TransitMode.TRAIN);
        assertThat(train.status()).isEqualTo(CandidateStatus.NO_SERVICE);
        assertThat(train.departures()).isEmpty();
        assertThat(train.durationMin()).isNull();
        // NO_SERVICE는 durationMin만 비운다 — legs·fare·accessMin·egressMin·referenceAt은
        // 특정 편에 좌우되지 않는 구조적인 값이라 여전히 채운다({@code doorToDoorCandidate})
        assertThat(train.legs()).hasSize(1);
        assertThat(train.fare()).isEqualTo(59800);
        assertThat(train.accessMin()).isEqualTo(0);
        assertThat(train.egressMin()).isEqualTo(0);
        assertThat(train.referenceAt()).isEqualTo("14:10");
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
                .willReturn(List.of(expressBusPath()));
        givenZeroAccessEgress(LAT_SEOUL, LNG_SEOUL, LAT_BUSAN, LNG_BUSAN);
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

    /**
     * 접근 지점(김포공항역 인근). {@code from} 블록(서울 시내)과 다른 좌표라 접근이 0분이
     * 아니라 실제 도보+지하철 소요(52분)를 갖는다 — Task 10이 채우는 door-to-door 필드를
     * 전부 실제로 검증하려면 접근·이탈이 0이 아닌 픽스처가 필요하다.
     */
    private static final double LAT_BOARDING = 37.5583, LNG_BOARDING = 126.8010;
    /** 하차 지점(제주공항). {@code to} 블록(제주 숙소, {@link #LAT_JEJU}·{@link #LNG_JEJU})과 다른 좌표다 */
    private static final double LAT_ALIGHTING = 33.5104, LNG_ALIGHTING = 126.4930;

    /** 서울 시내 출발지. {@link #LAT_BOARDING}과 달라야 접근이 0분이 아니게 된다 */
    private Block hotelBlock() {
        return blockAt(1L, 37.5000, 127.0000, 1, LocalTime.of(16, 0));  // 16:00+60분=base 17:00
    }

    /** 제주 숙소. {@link #LAT_ALIGHTING}과 달라야 이탈이 0분이 아니게 된다 */
    private Block jejuLodgingBlock() {
        return blockAt(2L, LAT_JEJU, LNG_JEJU);
    }

    /** 도보(3)+지하철(1) 접근 경로. 52분·1,550원 */
    private OdsayRouteResponse.Path accessRoute() {
        return new OdsayRouteResponse.Path(1,
                new OdsayRouteResponse.Info(52, 1550, null, null, null, null, null, "호텔", "김포공항역"),
                List.of(new OdsayRouteResponse.SubPath(3, 10, null, "호텔", "환승역", null),
                        new OdsayRouteResponse.SubPath(1, 42, null, "환승역", "김포공항역", null)));
    }

    /** 도보(3)+버스(2) 이탈 경로. 25분·1,200원 */
    private OdsayRouteResponse.Path egressRoute() {
        return new OdsayRouteResponse.Path(1,
                new OdsayRouteResponse.Info(25, 1200, null, null, null, null, null, "제주공항", "숙소"),
                List.of(new OdsayRouteResponse.SubPath(2, 10, null, "제주공항", "버스정류장", null),
                        new OdsayRouteResponse.SubPath(3, 15, null, "버스정류장", "숙소", null)));
    }

    /** 항공(pathType=13, 단일 leg). 승하차 지점이 {@link #LAT_BOARDING}·{@link #LAT_ALIGHTING}이다 */
    private OdsayRouteResponse.Path airRouteWithBoardingPoints() {
        return new OdsayRouteResponse.Path(13,
                new OdsayRouteResponse.Info(70, null, null, null, null, null, null,
                        "김포공항", "제주공항", 120200, 0),
                List.of(new OdsayRouteResponse.SubPath(7, 70, null, "김포공항", "제주공항", null,
                        3500010, 3500011, LNG_BOARDING, LAT_BOARDING, LNG_ALIGHTING, LAT_ALIGHTING,
                        null, null, null, null, null, null)));
    }

    @Test
    @DisplayName("시외 후보는 접근·대기·시외·이탈을 door-to-door로 합친다(legs 5개·transferCount 2)")
    void 시외_후보는_door_to_door_전체_경로를_담는다() {
        Block from = hotelBlock();
        Block to = jejuLodgingBlock();
        given(blockRepository.findAllByIdInAndProject_IdAndDeletedAtIsNull(List.of(1L, 2L), PROJECT_ID))
                .willReturn(List.of(from, to));
        given(projectRepository.findByIdAndDeletedAtIsNull(PROJECT_ID)).willReturn(Optional.of(publicProject()));
        given(publicTransitQueryService.getCombinedRoutes(
                37.5000, 127.0000, LAT_JEJU, LNG_JEJU))
                .willReturn(List.of(airRouteWithBoardingPoints()));
        given(publicTransitQueryService.getCombinedRoutes(37.5000, 127.0000, LAT_BOARDING, LNG_BOARDING))
                .willReturn(List.of(accessRoute()));
        given(publicTransitQueryService.getCombinedRoutes(LAT_ALIGHTING, LNG_ALIGHTING, LAT_JEJU, LNG_JEJU))
                .willReturn(List.of(egressRoute()));
        // base(17:00=1020) + accessMin(52) = accessArrival 17:52. AIR 여유(40분)를 더한
        // 기준(18:32) 이후 편만 남아야 하므로 18:40발을 둔다
        given(transitScheduleQueryService.getFlightSchedule(eq(3500010), eq(3500011), any(LocalDate.class)))
                .willReturn(List.of(TransitScheduleResDTO.FlightSchedule.builder()
                        .airline("제주항공").flightNo("7C300")
                        .departureTime("18:40").arrivalTime("19:50").runDay("매일")
                        .build()));

        TransitCandidateResDTO.Result result = service.calculate(PROJECT_ID, List.of(1L, 2L), null);

        Candidate air = candidateOf(result, TransitMode.AIR);
        assertThat(air.status()).isEqualTo(CandidateStatus.OK);
        assertThat(air.accessMin()).isEqualTo(52);
        assertThat(air.egressMin()).isEqualTo(25);
        // 기준 시각 = 접근 도착(17:52) + 항공 탑승 여유(40분) = 18:32
        assertThat(air.referenceAt()).isEqualTo("18:32");
        assertThat(air.departures()).hasSize(1);
        TransitCandidateResDTO.Departure departure = air.departures().get(0);
        // 대기 = 출발(18:40) - 접근 도착(17:52) = 48분
        assertThat(departure.waitMin()).isEqualTo(48);
        // legs = 접근 2개(도보·지하철) + 시외 1개(항공) + 이탈 2개(도보·버스) = 5개
        assertThat(air.legs()).hasSize(5);
        assertThat(air.legs()).extracting(TransitLegResDTO.Leg::type).containsExactly(
                TransitLegResDTO.LegType.WALK, TransitLegResDTO.LegType.SUBWAY,
                TransitLegResDTO.LegType.AIR,
                TransitLegResDTO.LegType.BUS, TransitLegResDTO.LegType.WALK);
        // 환승 횟수 = 탈것 leg 수(지하철·항공·버스=3) - 1 = 2. 도보는 세지 않는다
        assertThat(air.transferCount()).isEqualTo(2);
        // 요금 = 접근(1550) + 시외 totalPayment(120200) + 이탈(1200) = 122950
        assertThat(air.fare()).isEqualTo(122950);
        assertThat(air.fareConfidence()).isEqualTo(TransitResDTO.FareConfidence.CONFIRMED);
        // durationMin == (마지막 도착시각(19:50) - base(17:00)) + egressMin(25) = 170+25 = 195
        assertThat(air.durationMin()).isEqualTo(195);
    }

    @Test
    @DisplayName("접근·시외·이탈 요금 중 하나라도 없으면 fareConfidence는 UNKNOWN이고 fare는 null이다")
    void 요금_한_조각이라도_없으면_UNKNOWN이다() {
        given(blockRepository.findAllByIdInAndProject_IdAndDeletedAtIsNull(List.of(1L, 2L), PROJECT_ID))
                .willReturn(List.of(seoulBlock(), busanBlock()));
        given(projectRepository.findByIdAndDeletedAtIsNull(PROJECT_ID)).willReturn(Optional.of(publicProject()));
        given(publicTransitQueryService.getCombinedRoutes(anyDouble(), anyDouble(), anyDouble(), anyDouble()))
                .willReturn(List.of(trainPath()));
        given(publicTransitQueryService.getCombinedRoutes(LAT_SEOUL, LNG_SEOUL, LAT_SEOUL, LNG_SEOUL))
                .willReturn(List.of(pathOf(0, 0, null, null)));           // 접근 요금은 있다(0원)
        given(publicTransitQueryService.getCombinedRoutes(LAT_BUSAN, LNG_BUSAN, LAT_BUSAN, LNG_BUSAN))
                .willReturn(List.of(pathOf(0, null, null, null)));        // 이탈 요금이 없다
        given(transitScheduleQueryService.getTrainSchedule(anyInt(), anyInt(), any(LocalDate.class)))
                .willReturn(List.of(train("KTX", 1, "16:00", "18:37", 59800)));

        Candidate train = candidateOf(service.calculate(PROJECT_ID, List.of(1L, 2L), null), TransitMode.TRAIN);

        assertThat(train.fare()).isNull();
        assertThat(train.fareConfidence()).isEqualTo(TransitResDTO.FareConfidence.UNKNOWN);
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
        // 오늘 요일로 편을 거르고 요금을 고르느니 아예 묻지 않는다
        verifyNoInteractions(transitScheduleQueryService);
        // 수단 슬롯은 남되 탈 편은 정해지지 않았다 — 그래도 ODsay가 이 수단의 경로 자체는 줬으므로
        // 그 경로 자신의 시각·leg은 채운다(브리프 상태 분기 3: "시간표 미적용 → OK, ODsay 시외
        // leg 시간 사용")
        Candidate train = candidateOf(result, TransitMode.TRAIN);
        assertThat(train.departures()).isEmpty();
        assertThat(train.legs()).hasSize(1);
        assertThat(train.durationMin()).isEqualTo(157);
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

        // 각 구간은 앞 구간과 무관하게 독립적으로 project.startDate를 확인한다 — 두 번째 구간도
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

    /** 시외 구간의 기준 시각(base)이 13:00+60분=14:00이 되도록 startTime을 채운다 */
    private Block seoulBlock() {
        return blockAt(1L, LAT_SEOUL, LNG_SEOUL, 1, LocalTime.of(13, 0));
    }

    /** 시외 구간의 기준 시각(base)이 08:00+60분=09:00이 되도록 startTime을 채운다 */
    private Block busanBlock() {
        return blockAt(2L, LAT_BUSAN, LNG_BUSAN, 1, LocalTime.of(8, 0));
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
                .transportPref(TransportPref.PUBLIC)
                .startDate(LocalDate.of(2026, 8, 10))
                .build();
    }

    /**
     * 기차 경로(pathType=11). ODsay는 시외 경로에 payment를 주지 않지만 totalPayment는 준다
     * (실측 216/216) — 승하차 지점은 {@link #LAT_SEOUL}·{@link #LAT_BUSAN}과 같은 좌표라
     * 접근·이탈이 0분·0원인 경로로 단순화할 수 있다({@code givenZeroAccessEgress}).
     *
     * <p>startID·endID(3300128 서울역·3300108 부산역)는 실측 픽스처
     * ({@code schedule-train.json})와 같은 값이다 — 이름 검색 없이 이 ID가 그대로
     * {@code trainServiceTime}에 들어간다.
     */
    private OdsayRouteResponse.Path trainPath() {
        return new OdsayRouteResponse.Path(11,
                new OdsayRouteResponse.Info(157, null, null, 325000, 800, null, null, "서울", "부산", 59800, 0),
                List.of(new OdsayRouteResponse.SubPath(4, 157, 325000, "서울", "부산", null,
                        3300128, 3300108, LNG_SEOUL, LAT_SEOUL, LNG_BUSAN, LAT_BUSAN,
                        null, null, null, null, null, null)));
    }

    /**
     * 역 ID가 알려진 대역(3300·3400·3500·3600·4000xxx) 밖인 기차 경로. 추측하지 않고
     * 그 수단만 조회 실패로 빠지는지를 검증하는 데 쓴다.
     */
    private OdsayRouteResponse.Path unknownBandTrainPath() {
        return new OdsayRouteResponse.Path(11,
                new OdsayRouteResponse.Info(157, null, null, 325000, 800, null, null, "서울", "부산"),
                List.of(new OdsayRouteResponse.SubPath(4, 157, 325000, "서울", "부산", null,
                        9_999_999, 9_999_998, null, null, null, null, null, null, null, null, null, null)));
    }

    /**
     * 고속버스(tt5) 경로(pathType=12). startID·endID(4000057·4000156)는
     * {@code OdsayClientTest}의 터미널 검색 응답과 같은 값이다.
     */
    private OdsayRouteResponse.Path expressBusPath() {
        return new OdsayRouteResponse.Path(12,
                new OdsayRouteResponse.Info(240, null, null, null, null, null, null,
                        "서울고속버스터미널", "부산종합버스터미널", 39700, 0),
                List.of(new OdsayRouteResponse.SubPath(5, 240, null,
                        "서울고속버스터미널", "부산종합버스터미널", null,
                        4000057, 4000156, LNG_SEOUL, LAT_SEOUL, LNG_BUSAN, LAT_BUSAN,
                        null, null, null, null, null, null)));
    }

    /**
     * 시외버스(tt6) 경로(pathType=12). startID·endID(3600210 서부정류장·4000135 광주종합버스터미널)는
     * 실측 사실 문서 §5의 예시 그대로다 — trafficType이 6이어도 고속버스(tt5)와 같은
     * {@code searchInterBusSchedule}로 간다.
     */
    private OdsayRouteResponse.Path interCityBusPath() {
        return new OdsayRouteResponse.Path(12,
                new OdsayRouteResponse.Info(140, null, null, null, null, null, null,
                        "서부정류장", "광주종합버스터미널", 20900, 0),
                List.of(new OdsayRouteResponse.SubPath(6, 140, null,
                        "서부정류장", "광주종합버스터미널", null,
                        3600210, 4000135, LNG_SEOUL, LAT_SEOUL, LNG_BUSAN, LAT_BUSAN,
                        null, null, null, null, null, null)));
    }

    /** 해운 경로(pathType=14). 기차와 마찬가지로 요금도 환승 수도 없다 */
    private OdsayRouteResponse.Path ferryPath() {
        return new OdsayRouteResponse.Path(14,
                new OdsayRouteResponse.Info(150, null, null, 98000, 600, null, null, "목포", "홍도"),
                List.of(new OdsayRouteResponse.SubPath(7, 150, 98000, "목포항", "홍도항", null)));
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

    /**
     * 항공 구간(trafficType=7)이 섞인 복합 경로(pathType=20).
     *
     * <p>startID·endID(3500005 청주공항·3500003 제주공항)는 {@code DomesticAirport} enum의
     * 등록 ID와 같다 — 실측으로 확인된 값이다. 이름 검색({@code DomesticAirport.findByName}) 없이
     * 이 ID가 그대로 {@code airServiceTime}에 들어간다.
     */
    private OdsayRouteResponse.Path airPath() {
        return new OdsayRouteResponse.Path(20,
                new OdsayRouteResponse.Info(356, null, null, 436642, null, null, null, "청주", "제주", 120200, 0),
                List.of(new OdsayRouteResponse.SubPath(7, 356, 436642, "청주공항", "제주공항", null,
                        3500005, 3500003, LNG_BUSAN, LAT_BUSAN, LNG_JEJU, LAT_JEJU,
                        null, null, null, null, null, null)));
    }

    /**
     * 환승 경로(pathType=20). 첫 leg는 기차(서울역 3300128→광주송정 3300140), 두 번째 leg는
     * 항공(광주공항 3500008→제주공항 3500003)이다 — {@code IntercityLegsTest.복합_경로()}와 같은
     * ID를 쓴다. 대표 수단은 첫 leg 기준(기차)이고, 두 번째 leg는 서로 다른 역 ID 대역이라
     * 시간표 API도 다르다 — get(0)↔get(size-1) 스왑을 잡아내는 유일한 픽스처다.
     */
    private OdsayRouteResponse.Path transferTrainAirPath() {
        return new OdsayRouteResponse.Path(20,
                new OdsayRouteResponse.Info(220, null, null, 400000, null, null, null, "서울", "제주", 119800, 1),
                List.of(
                        new OdsayRouteResponse.SubPath(4, 157, 300000, "서울역", "광주송정", null,
                                3300128, 3300140, LNG_SEOUL, LAT_SEOUL, null, null,
                                null, null, null, null, null, null),
                        new OdsayRouteResponse.SubPath(7, 63, 400000, "광주공항", "제주공항", null,
                                3500008, 3500003, null, null, LNG_JEJU, LAT_JEJU,
                                null, null, null, null, null, null)));
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

    // --- accessLegsOf: 접근·이탈 경로 별도 조회 (Task 7) ---

    /**
     * 승차 지점 → 하차 지점을 단일 leg로 잇는 시외 대표경로. {@code path()}·{@code mode()}는
     * accessLegsOf가 쓰지 않으므로 대충 둔다 — 이 테스트가 검증하는 것은 boarding/alightingPoint뿐이다.
     */
    private IntercityLegs intercityLegsBoardingAt(
            double boardingX, double boardingY, double alightingX, double alightingY) {
        OdsayRouteResponse.SubPath leg = new OdsayRouteResponse.SubPath(4, 157, 325000, "서울", "부산", null,
                3300128, 3300108, boardingX, boardingY, alightingX, alightingY,
                59800, null, null, null, null, "KTX");
        OdsayRouteResponse.Path path = new OdsayRouteResponse.Path(11,
                new OdsayRouteResponse.Info(157, null, null, 325000, null, null, null, "서울", "부산"),
                List.of(leg));
        return new IntercityLegs(path, List.of(leg), TransitMode.TRAIN);
    }

    private List<OdsayRouteResponse.Path> readFixture(String name) throws IOException {
        try (InputStream in = getClass().getResourceAsStream("/odsay/" + name)) {
            return new ObjectMapper().readValue(in, OdsayRouteResponse.class).result().path();
        }
    }

    @Test
    @DisplayName("[실측] 서울시청→서울역 접근 경로는 7분·1,550원·3leg(도보·지하철·도보)다(odsay-access.json)")
    void 측정_서울시청_서울역_접근경로() throws IOException {
        Block from = blockAt(1L, LAT_A, LNG_A);
        Block to = blockAt(2L, LAT_JEJU, LNG_JEJU);
        // boardingPoint=서울역(126.970681, 37.554522) — odsay-intercity.json 실측 픽스처와 같은 좌표
        IntercityLegs legs = intercityLegsBoardingAt(126.970681, 37.554522, LNG_BUSAN, LAT_BUSAN);

        given(publicTransitQueryService.getCombinedRoutes(LAT_A, LNG_A, 37.554522, 126.970681))
                .willReturn(readFixture("odsay-access.json"));
        given(publicTransitQueryService.getCombinedRoutes(LAT_BUSAN, LNG_BUSAN, LAT_JEJU, LNG_JEJU))
                .willReturn(List.of(pathOf(5, 300, null, null)));

        TransitCandidateServiceImpl.AccessLegs result =
                service.accessLegsOf(new TransitCandidateServiceImpl.Pair(from, to), legs).orElseThrow();

        assertThat(result.accessMin()).isEqualTo(7);
        assertThat(result.accessFare()).isEqualTo(1550);
        assertThat(result.access()).extracting(TransitLegResDTO.Leg::type)
                .containsExactly(
                        TransitLegResDTO.LegType.WALK,
                        TransitLegResDTO.LegType.SUBWAY,
                        TransitLegResDTO.LegType.WALK);
        // 이탈도 같은 메서드로 채워진다 — 접근과 뒤섞이지 않았는지 다른 값으로 확인한다
        assertThat(result.egressMin()).isEqualTo(5);
        assertThat(result.egressFare()).isEqualTo(300);
    }

    @Test
    @DisplayName("접근은 블록→승차지점, 이탈은 하차지점→블록 순서로 조회한다 — 방향이 바뀌면 오답이다")
    void 접근_이탈_호출_순서를_검증한다() {
        Block from = blockAt(1L, LAT_A, LNG_A);
        Block to = blockAt(2L, LAT_JEJU, LNG_JEJU);
        IntercityLegs legs = intercityLegsBoardingAt(126.970681, 37.554522, LNG_BUSAN, LAT_BUSAN);
        given(publicTransitQueryService.getCombinedRoutes(anyDouble(), anyDouble(), anyDouble(), anyDouble()))
                .willReturn(List.of(pathOf(5, 300, null, null)));

        service.accessLegsOf(new TransitCandidateServiceImpl.Pair(from, to), legs);

        ArgumentCaptor<Double> startLat = ArgumentCaptor.forClass(Double.class);
        ArgumentCaptor<Double> startLng = ArgumentCaptor.forClass(Double.class);
        ArgumentCaptor<Double> endLat = ArgumentCaptor.forClass(Double.class);
        ArgumentCaptor<Double> endLng = ArgumentCaptor.forClass(Double.class);
        verify(publicTransitQueryService, times(2))
                .getCombinedRoutes(startLat.capture(), startLng.capture(), endLat.capture(), endLng.capture());

        // 첫 호출(접근) = 블록 좌표 → 승차 지점
        assertThat(startLat.getAllValues().get(0)).isEqualTo(LAT_A);
        assertThat(startLng.getAllValues().get(0)).isEqualTo(LNG_A);
        assertThat(endLat.getAllValues().get(0)).isEqualTo(37.554522);
        assertThat(endLng.getAllValues().get(0)).isEqualTo(126.970681);

        // 두번째 호출(이탈) = 하차 지점 → 블록 좌표. 뒤집히면(승차 지점→블록, 블록→하차 지점) 이 값이 깨진다
        assertThat(startLat.getAllValues().get(1)).isEqualTo(LAT_BUSAN);
        assertThat(startLng.getAllValues().get(1)).isEqualTo(LNG_BUSAN);
        assertThat(endLat.getAllValues().get(1)).isEqualTo(LAT_JEJU);
        assertThat(endLng.getAllValues().get(1)).isEqualTo(LNG_JEJU);
    }

    @Test
    @DisplayName("접근 경로가 없으면 empty다 — 이탈은 부르지도 않고, 0분으로 추측하지 않는다")
    void 접근_경로가_없으면_empty를_반환한다() {
        Block from = blockAt(1L, LAT_A, LNG_A);
        Block to = blockAt(2L, LAT_JEJU, LNG_JEJU);
        IntercityLegs legs = intercityLegsBoardingAt(126.970681, 37.554522, LNG_BUSAN, LAT_BUSAN);
        given(publicTransitQueryService.getCombinedRoutes(LAT_A, LNG_A, 37.554522, 126.970681))
                .willThrow(new TransitException(TransitErrorCode.ROUTE_NOT_FOUND));

        Optional<TransitCandidateServiceImpl.AccessLegs> result =
                service.accessLegsOf(new TransitCandidateServiceImpl.Pair(from, to), legs);

        assertThat(result).isEmpty();
        verify(publicTransitQueryService, never())
                .getCombinedRoutes(LAT_BUSAN, LNG_BUSAN, LAT_JEJU, LNG_JEJU);
    }

    @Test
    @DisplayName("접근은 있어도 이탈 경로가 없으면 empty다")
    void 이탈_경로가_없으면_empty를_반환한다() throws IOException {
        Block from = blockAt(1L, LAT_A, LNG_A);
        Block to = blockAt(2L, LAT_JEJU, LNG_JEJU);
        IntercityLegs legs = intercityLegsBoardingAt(126.970681, 37.554522, LNG_BUSAN, LAT_BUSAN);
        given(publicTransitQueryService.getCombinedRoutes(LAT_A, LNG_A, 37.554522, 126.970681))
                .willReturn(readFixture("odsay-access.json"));
        given(publicTransitQueryService.getCombinedRoutes(LAT_BUSAN, LNG_BUSAN, LAT_JEJU, LNG_JEJU))
                .willThrow(new TransitException(TransitErrorCode.ROUTE_NOT_FOUND));

        Optional<TransitCandidateServiceImpl.AccessLegs> result =
                service.accessLegsOf(new TransitCandidateServiceImpl.Pair(from, to), legs);

        assertThat(result).isEmpty();
    }
}
