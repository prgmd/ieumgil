package com.ssafy.ieumgil.domain.chatbot.tool;

import com.ssafy.ieumgil.domain.chatbot.dto.ChatbotReqDTO;
import com.ssafy.ieumgil.domain.chatbot.dto.ChatbotResDTO;
import com.ssafy.ieumgil.domain.place.dto.PlaceResDTO;
import com.ssafy.ieumgil.domain.place.exception.PlaceErrorCode;
import com.ssafy.ieumgil.domain.place.exception.PlaceException;
import com.ssafy.ieumgil.domain.place.service.PlaceQueryService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 지도 기반 추천 모드의 장소검색 tool (BOT-03).
 *
 * <p>일반 모드 tool과 결정적으로 다른 점은 검색어에 목적지 문자열을 붙이지 않고
 * 사용자가 보고 있는 지도 범위로 결과를 한정한다는 것이다.
 */
@ExtendWith(MockitoExtension.class)
class ViewportPlaceSearchToolTest {

    private static final ChatbotReqDTO.MapContext VIEWPORT =
            new ChatbotReqDTO.MapContext(33.44, 126.93, 33.47, 126.95);

    @Mock
    private PlaceQueryService placeQueryService;

    private static PlaceResDTO.Place place(String id, String name) {
        return PlaceResDTO.Place.builder()
                .placeId(id).name(name).address("제주 서귀포시")
                .lat(33.45).lng(126.93).category("카페")
                .build();
    }

    /** 재정렬 기준. 대부분의 테스트는 순서를 보지 않으므로 뷰포트 중심만 있으면 된다 */
    private static final PlaceRanker.RankingContext RANKING_CONTEXT =
            new PlaceRanker.RankingContext(List.of(), new PlaceRanker.Anchor(33.455, 126.94, null), List.of());

    @Test
    @DisplayName("뷰포트 범위로 검색하고 목적지 문자열은 붙이지 않는다 — 보이는 범위가 곧 범위다")
    void searchesWithinViewportWithoutPrefixingDestination() {
        when(placeQueryService.searchPlacesInRect("카페", 33.44, 126.93, 33.47, 126.95))
                .thenReturn(List.of(place("1", "스타벅스 성산일출봉점")));
        ViewportPlaceSearchTool tool = new ViewportPlaceSearchTool(
                VIEWPORT, placeQueryService, new CandidateCollector(), RANKING_CONTEXT);

        List<PlaceSearchSummary> result = tool.searchPlacesInView("카페");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).name()).isEqualTo("스타벅스 성산일출봉점");
        verify(placeQueryService, never()).searchPlaces(anyString(), anyDouble(), anyDouble());
    }

    @Test
    @DisplayName("검색 결과가 수집기에 담긴다 — 지도 모드도 같은 수집기를 재사용한다")
    void resultsAreCollectedAsCandidates() {
        when(placeQueryService.searchPlacesInRect(anyString(), anyDouble(), anyDouble(), anyDouble(), anyDouble()))
                .thenReturn(List.of(place("77", "카페")));
        CandidateCollector collector = new CandidateCollector();
        ViewportPlaceSearchTool tool = new ViewportPlaceSearchTool(
                VIEWPORT, placeQueryService, collector, RANKING_CONTEXT);

        tool.searchPlacesInView("카페");

        assertThat(collector.candidates()).hasSize(1);
        assertThat(collector.candidates().get(0).placeId()).isEqualTo("77");
    }

    @Test
    @DisplayName("범위 안에 결과가 없으면 빈 목록이다 — 모델이 억지로 지어내지 않게 프롬프트가 받쳐준다")
    void emptyViewportYieldsEmptyList() {
        when(placeQueryService.searchPlacesInRect(anyString(), anyDouble(), anyDouble(), anyDouble(), anyDouble()))
                .thenReturn(List.of());
        ViewportPlaceSearchTool tool = new ViewportPlaceSearchTool(
                VIEWPORT, placeQueryService, new CandidateCollector(), RANKING_CONTEXT);

        assertThat(tool.searchPlacesInView("카페")).isEmpty();
    }

    @Test
    @DisplayName("카카오 호출이 실패하면 빈 목록으로 위장하지 않고 예외를 올린다 — 모델이 실패를 '없음'으로 오인하지 않게")
    void kakaoFailureSurfacesAsExceptionNotEmptyList() {
        when(placeQueryService.searchPlacesInRect(anyString(), anyDouble(), anyDouble(), anyDouble(), anyDouble()))
                .thenThrow(new PlaceException(PlaceErrorCode.KAKAO_API_CALL_FAILED));
        ViewportPlaceSearchTool tool = new ViewportPlaceSearchTool(
                VIEWPORT, placeQueryService, new CandidateCollector(), RANKING_CONTEXT);

        assertThatThrownBy(() -> tool.searchPlacesInView("카페"))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    @DisplayName("재정렬한 뒤 상한만큼만 모델에 넘긴다")
    void ranksThenTruncatesForModel() {
        when(placeQueryService.searchPlacesInRect(anyString(), anyDouble(), anyDouble(), anyDouble(), anyDouble()))
                .thenReturn(manyPlaces(15));

        CandidateCollector collector = new CandidateCollector();
        ViewportPlaceSearchTool tool = new ViewportPlaceSearchTool(
                VIEWPORT, placeQueryService, collector, RANKING_CONTEXT);

        List<PlaceSearchSummary> summaries = tool.searchPlacesInView("카페");

        assertThat(summaries).hasSize(8);
        // 카드는 모델에 넘긴 것과 같은 집합이어야 한다 — 텍스트에 없는 카드가 뜨면 안 된다
        assertThat(collector.candidates()).hasSize(8);
    }

    @Test
    @DisplayName("재정렬이 터져도 카카오 순서로 카드를 낸다 — 순위를 잃는 건 감수해도 카드를 잃으면 안 된다")
    void rankingFailureFallsBackToKakaoOrder() {
        when(placeQueryService.searchPlacesInRect(anyString(), anyDouble(), anyDouble(), anyDouble(), anyDouble()))
                .thenReturn(manyPlaces(15));
        CandidateCollector collector = new CandidateCollector();
        // 기준이 null 이면 재정렬은 NPE 로 죽는다. 이 tool 의 존재 이유는 재정렬이 아니라 카드다.
        ViewportPlaceSearchTool tool = new ViewportPlaceSearchTool(
                VIEWPORT, placeQueryService, collector, null);

        List<PlaceSearchSummary> summaries = tool.searchPlacesInView("카페");

        assertThat(summaries).hasSize(8);
        assertThat(summaries.get(0).name()).isEqualTo("카페0");
        assertThat(collector.candidates()).hasSize(8);
    }

    @Test
    @DisplayName("첫 검색이 결과를 주면 축약 재시도를 하지 않는다 — 불필요한 카카오 호출 금지")
    void doesNotRetryWhenFirstSearchHits() {
        when(placeQueryService.searchPlacesInRect("실내 관광지", 33.44, 126.93, 33.47, 126.95))
                .thenReturn(List.of(place("1", "제주 박물관")));
        ViewportPlaceSearchTool tool = new ViewportPlaceSearchTool(
                VIEWPORT, placeQueryService, new CandidateCollector(), RANKING_CONTEXT);

        assertThat(tool.searchPlacesInView("실내 관광지"))
                .extracting(PlaceSearchSummary::name).containsExactly("제주 박물관");
        verify(placeQueryService, times(1))
                .searchPlacesInRect(anyString(), anyDouble(), anyDouble(), anyDouble(), anyDouble());
    }

    @Test
    @DisplayName("0건이면 붙여쓰기부터 재시도하고, 첫 폴백이 맞으면 거기서 멈춘다")
    void retriesWithConcatenatedKeywordAndStopsAtFirstHit() {
        when(placeQueryService.searchPlacesInRect("실내 관광지 아이", 33.44, 126.93, 33.47, 126.95))
                .thenReturn(List.of());
        // 1번 후보는 축약("아이")이 아니라 붙여쓰기다 — 의도를 버리기 전에 보존부터 시도한다
        when(placeQueryService.searchPlacesInRect("실내관광지아이", 33.44, 126.93, 33.47, 126.95))
                .thenReturn(List.of(place("2", "키즈카페")));
        CandidateCollector collector = new CandidateCollector();
        ViewportPlaceSearchTool tool = new ViewportPlaceSearchTool(
                VIEWPORT, placeQueryService, collector, RANKING_CONTEXT);

        assertThat(tool.searchPlacesInView("실내 관광지 아이"))
                .extracting(PlaceSearchSummary::name).containsExactly("키즈카페");
        // 폴백 결과도 같은 수집 경로를 타야 카드가 뜬다
        assertThat(collector.candidates()).extracting(ChatbotResDTO.Candidate::placeId).containsExactly("2");
        verify(placeQueryService, times(2))
                .searchPlacesInRect(anyString(), anyDouble(), anyDouble(), anyDouble(), anyDouble());
    }

    @Test
    @DisplayName("앞선 폴백이 전부 0건이면 마지막 후보까지 간다 — 카카오 호출은 원 호출 포함 4회")
    void fallsBackUntilACandidateHits() {
        when(placeQueryService.searchPlacesInRect("부산 박물관 아쿠아리움", 33.44, 126.93, 33.47, 126.95))
                .thenReturn(List.of());
        when(placeQueryService.searchPlacesInRect("부산박물관아쿠아리움", 33.44, 126.93, 33.47, 126.95))
                .thenReturn(List.of());
        when(placeQueryService.searchPlacesInRect("아쿠아리움", 33.44, 126.93, 33.47, 126.95))
                .thenReturn(List.of());
        when(placeQueryService.searchPlacesInRect("박물관", 33.44, 126.93, 33.47, 126.95))
                .thenReturn(List.of(place("3", "부산박물관")));
        ViewportPlaceSearchTool tool = new ViewportPlaceSearchTool(
                VIEWPORT, placeQueryService, new CandidateCollector(), RANKING_CONTEXT);

        assertThat(tool.searchPlacesInView("부산 박물관 아쿠아리움"))
                .extracting(PlaceSearchSummary::name).containsExactly("부산박물관");
        verify(placeQueryService, times(4))
                .searchPlacesInRect(anyString(), anyDouble(), anyDouble(), anyDouble(), anyDouble());
    }

    @Test
    @DisplayName("폴백까지 전부 0건이면 예전처럼 빈 목록이다 — 상한 3회를 넘겨 더 때리지 않는다")
    void allEmptyStillReturnsEmptyListWithinRetryCap() {
        when(placeQueryService.searchPlacesInRect(anyString(), anyDouble(), anyDouble(), anyDouble(), anyDouble()))
                .thenReturn(List.of());
        ViewportPlaceSearchTool tool = new ViewportPlaceSearchTool(
                VIEWPORT, placeQueryService, new CandidateCollector(), RANKING_CONTEXT);

        assertThat(tool.searchPlacesInView("사진 촬영 명소")).isEmpty();
        verify(placeQueryService).searchPlacesInRect("사진 촬영 명소", 33.44, 126.93, 33.47, 126.95);
        verify(placeQueryService).searchPlacesInRect("사진촬영명소", 33.44, 126.93, 33.47, 126.95);
        verify(placeQueryService).searchPlacesInRect("명소", 33.44, 126.93, 33.47, 126.95);
        verify(placeQueryService).searchPlacesInRect("촬영", 33.44, 126.93, 33.47, 126.95);
        verify(placeQueryService, times(4))
                .searchPlacesInRect(anyString(), anyDouble(), anyDouble(), anyDouble(), anyDouble());
    }

    @Test
    @DisplayName("단일 토큰 검색어는 0건이어도 재시도하지 않는다 — 축약할 것이 없다")
    void singleTokenKeywordIsNotRetried() {
        when(placeQueryService.searchPlacesInRect("편의점", 33.44, 126.93, 33.47, 126.95))
                .thenReturn(List.of());
        ViewportPlaceSearchTool tool = new ViewportPlaceSearchTool(
                VIEWPORT, placeQueryService, new CandidateCollector(), RANKING_CONTEXT);

        assertThat(tool.searchPlacesInView("편의점")).isEmpty();
        verify(placeQueryService, times(1))
                .searchPlacesInRect(anyString(), anyDouble(), anyDouble(), anyDouble(), anyDouble());
    }

    @Test
    @DisplayName("검색이 예외를 던지면 폴백을 타지 않는다 — 0건과 실패는 다른 신호다")
    void exceptionIsNotRetried() {
        when(placeQueryService.searchPlacesInRect(anyString(), anyDouble(), anyDouble(), anyDouble(), anyDouble()))
                .thenThrow(new PlaceException(PlaceErrorCode.KAKAO_API_CALL_FAILED));
        ViewportPlaceSearchTool tool = new ViewportPlaceSearchTool(
                VIEWPORT, placeQueryService, new CandidateCollector(), RANKING_CONTEXT);

        assertThatThrownBy(() -> tool.searchPlacesInView("사진 촬영 명소"))
                .isInstanceOf(IllegalStateException.class);
        verify(placeQueryService, times(1))
                .searchPlacesInRect(anyString(), anyDouble(), anyDouble(), anyDouble(), anyDouble());
    }

    private static List<PlaceResDTO.Place> manyPlaces(int count) {
        return IntStream.range(0, count)
                .mapToObj(i -> PlaceResDTO.Place.builder()
                        .placeId("p" + i).name("카페" + i).address("제주")
                        .lat(33.45).lng(126.93).category("카페")
                        .categoryPath("음식점 > 카페").build())
                .toList();
    }
}
