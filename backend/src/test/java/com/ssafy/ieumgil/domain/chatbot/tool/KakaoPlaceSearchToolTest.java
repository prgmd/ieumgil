package com.ssafy.ieumgil.domain.chatbot.tool;

import com.ssafy.ieumgil.domain.block.entity.Block;
import com.ssafy.ieumgil.domain.block.entity.BlockCategory;
import com.ssafy.ieumgil.domain.block.entity.BlockSource;
import com.ssafy.ieumgil.domain.chatbot.dto.ChatbotResDTO;
import com.ssafy.ieumgil.domain.place.dto.PlaceResDTO;
import com.ssafy.ieumgil.domain.place.service.PlaceQueryService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class KakaoPlaceSearchToolTest {

    @Mock
    private PlaceQueryService placeQueryService;

    @Test
    void returnsSummariesWithDerivedUrlForMatchingPlaces() {
        PlaceResDTO.Place place = PlaceResDTO.Place.builder()
                .placeId("12345").name("제주 흑돼지 맛집").address("제주 제주시").lat(33.5).lng(126.5).category("음식점")
                .build();
        when(placeQueryService.searchPlaces(eq("제주도 흑돼지"), isNull(), isNull()))
                .thenReturn(List.of(place));

        KakaoPlaceSearchTool tool = new KakaoPlaceSearchTool("제주도", placeQueryService, new CandidateCollector(), new KakaoPlaceCoordinateResolver(placeQueryService));

        List<PlaceSearchSummary> result = tool.searchPlaces("흑돼지", null);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).name()).isEqualTo("제주 흑돼지 맛집");
        assertThat(result.get(0).url()).isEqualTo("https://place.map.kakao.com/12345");
    }

    @Test
    void returnsEmptyListWhenNoResults() {
        when(placeQueryService.searchPlaces(eq("제주도 존재하지않는것"), isNull(), isNull()))
                .thenReturn(List.of());

        KakaoPlaceSearchTool tool = new KakaoPlaceSearchTool("제주도", placeQueryService, new CandidateCollector(), new KakaoPlaceCoordinateResolver(placeQueryService));

        assertThat(tool.searchPlaces("존재하지않는것", null)).isEmpty();
    }

    @Test
    @DisplayName("검색이 실패하면 빈 목록으로 위장하지 않고 예외를 올린다 — 모델이 실패를 '없음'으로 오인하지 않게")
    void surfacesExceptionInsteadOfEmptyListWhenSearchFails() {
        when(placeQueryService.searchPlaces(any(), any(), any())).thenThrow(new RuntimeException("kakao down"));

        KakaoPlaceSearchTool tool = new KakaoPlaceSearchTool("제주도", placeQueryService, new CandidateCollector(), new KakaoPlaceCoordinateResolver(placeQueryService));

        assertThatThrownBy(() -> tool.searchPlaces("흑돼지", null))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    @DisplayName("검색 결과가 수집기에 담긴다 — 응답 candidates[]의 원천이다")
    void searchResultsAreCollectedAsCandidates() {
        PlaceResDTO.Place place = PlaceResDTO.Place.builder()
                .placeId("77").name("스타벅스 성산일출봉점").address("제주 서귀포시")
                .lat(33.45).lng(126.93).category("카페")
                .build();
        when(placeQueryService.searchPlaces(anyString(), any(), any())).thenReturn(List.of(place));
        CandidateCollector collector = new CandidateCollector();
        KakaoPlaceSearchTool tool = new KakaoPlaceSearchTool("제주도", placeQueryService, collector, new KakaoPlaceCoordinateResolver(placeQueryService));

        tool.searchPlaces("카페", null);

        assertThat(collector.candidates()).hasSize(1);
        assertThat(collector.candidates().get(0).placeId()).isEqualTo("77");
    }

    @Test
    @DisplayName("기준 장소를 주면 그 좌표 주변을 거리순으로 검색한다 — 보드에 있는 블록이면 카카오 재검색 없이 좌표를 얻는다")
    void searchesNearGivenPlaceUsingBoardCoordinates() {
        Block onBoard = Block.builder()
                .startOffsetMinutes(0).orderKey("a0").name("성산일출봉")
                .category(BlockCategory.SPOT).durationMin(60).budget(0)
                .lat(java.math.BigDecimal.valueOf(33.4581)).lng(java.math.BigDecimal.valueOf(126.9425))
                .source(BlockSource.KAKAO)
                .build();
        PlaceResDTO.Place nearby = PlaceResDTO.Place.builder()
                .placeId("1").name("성산 카페").address("제주 서귀포시")
                .lat(33.4590).lng(126.9430).category("카페")
                .build();
        when(placeQueryService.searchPlaces("카페", 33.4581, 126.9425)).thenReturn(List.of(nearby));
        KakaoPlaceSearchTool tool = new KakaoPlaceSearchTool("제주도", placeQueryService,
                new CandidateCollector(),
                new KakaoPlaceCoordinateResolver(placeQueryService, () -> List.of(onBoard)));

        List<PlaceSearchSummary> result = tool.searchPlaces("카페", "성산일출봉");

        assertThat(result).extracting(PlaceSearchSummary::name).containsExactly("성산 카페");
        // 좌표가 범위를 좁히므로 목적지 접두사를 붙이지 않는다 — 붙이면 검색어 특이성만 떨어진다
        verify(placeQueryService, never()).searchPlaces(eq("제주도 카페"), any(), any());
    }

    @Test
    @DisplayName("기준 장소를 안 주면 기존대로 목적지 기준 검색이다")
    void withoutNearPlaceFallsBackToDestinationSearch() {
        PlaceResDTO.Place place = PlaceResDTO.Place.builder()
                .placeId("1").name("아무 카페").address("제주").lat(33.1).lng(126.1).category("카페")
                .build();
        when(placeQueryService.searchPlaces("제주도 카페", null, null)).thenReturn(List.of(place));
        KakaoPlaceSearchTool tool = new KakaoPlaceSearchTool("제주도", placeQueryService,
                new CandidateCollector(),
                new KakaoPlaceCoordinateResolver(placeQueryService, List::of));

        assertThat(tool.searchPlaces("카페", null))
                .extracting(PlaceSearchSummary::name).containsExactly("아무 카페");
    }

    @Test
    @DisplayName("기준 장소의 좌표를 못 구하면 목적지 기준 검색으로 떨어진다 — 조용히 빈 결과를 주지 않는다")
    void unresolvableNearPlaceFallsBackInsteadOfFailing() {
        PlaceResDTO.Place place = PlaceResDTO.Place.builder()
                .placeId("1").name("아무 카페").address("제주").lat(33.1).lng(126.1).category("카페")
                .build();
        // 기준 장소 해석 시도는 빈 결과, 목적지 기준 검색은 결과 있음
        when(placeQueryService.searchPlaces("제주도 없는곳", null, null)).thenReturn(List.of());
        when(placeQueryService.searchPlaces("제주도 카페", null, null)).thenReturn(List.of(place));
        KakaoPlaceSearchTool tool = new KakaoPlaceSearchTool("제주도", placeQueryService,
                new CandidateCollector(),
                new KakaoPlaceCoordinateResolver(placeQueryService, List::of));

        assertThat(tool.searchPlaces("카페", "없는곳"))
                .extracting(PlaceSearchSummary::name).containsExactly("아무 카페");
    }

    @Test
    @DisplayName("사용자 검색 상한(15)이 그대로 와도 챗봇 상한 5로 다시 자른다 — LLM 프롬프트 토큰 폭증 방지")
    void capsResultsAtChatbotLimitEvenWhenServiceReturnsUserSearchLimit() {
        List<PlaceResDTO.Place> fifteenPlaces = IntStream.range(0, 15)
                .mapToObj(i -> PlaceResDTO.Place.builder()
                        .placeId(String.valueOf(i)).name("카페" + i).address("제주")
                        .lat(33.1).lng(126.1).category("카페")
                        .build())
                .toList();
        when(placeQueryService.searchPlaces(anyString(), any(), any())).thenReturn(fifteenPlaces);
        CandidateCollector collector = new CandidateCollector();
        KakaoPlaceSearchTool tool = new KakaoPlaceSearchTool("제주도", placeQueryService, collector,
                new KakaoPlaceCoordinateResolver(placeQueryService));

        List<PlaceSearchSummary> result = tool.searchPlaces("카페", null);

        assertThat(result).hasSize(5);
        assertThat(collector.candidates()).hasSize(5);
    }

    private static PlaceResDTO.Place place(String id, String name) {
        return PlaceResDTO.Place.builder()
                .placeId(id).name(name).address("부산").lat(35.1).lng(129.0).category("관광명소")
                .build();
    }

    private KakaoPlaceSearchTool busanTool(CandidateCollector collector) {
        return new KakaoPlaceSearchTool("부산", placeQueryService, collector,
                new KakaoPlaceCoordinateResolver(placeQueryService, List::of));
    }

    @Test
    @DisplayName("첫 검색이 결과를 주면 축약 재시도를 하지 않는다 — 불필요한 카카오 호출 금지")
    void doesNotRetryWhenFirstSearchHits() {
        when(placeQueryService.searchPlaces("부산 실내 관광지", null, null))
                .thenReturn(List.of(place("1", "부산박물관")));

        assertThat(busanTool(new CandidateCollector()).searchPlaces("실내 관광지", null))
                .extracting(PlaceSearchSummary::name).containsExactly("부산박물관");
        verify(placeQueryService, times(1)).searchPlaces(anyString(), any(), any());
    }

    @Test
    @DisplayName("0건이면 붙여쓰기부터 재시도하고, 폴백 결과도 카드로 수집된다")
    void retriesWithConcatenatedKeywordAndCollectsFallbackResult() {
        when(placeQueryService.searchPlaces("부산 실내 관광지 아이", null, null)).thenReturn(List.of());
        // 1번 후보는 축약("아이")이 아니라 붙여쓰기다 — 의도를 버리기 전에 보존부터 시도한다
        when(placeQueryService.searchPlaces("부산 실내관광지아이", null, null)).thenReturn(List.of(place("2", "키즈카페")));
        CandidateCollector collector = new CandidateCollector();

        assertThat(busanTool(collector).searchPlaces("실내 관광지 아이", null))
                .extracting(PlaceSearchSummary::name).containsExactly("키즈카페");
        assertThat(collector.candidates()).extracting(ChatbotResDTO.Candidate::placeId).containsExactly("2");
        verify(placeQueryService, times(2)).searchPlaces(anyString(), any(), any());
    }

    @Test
    @DisplayName("폴백 질의도 목적지 접두사를 유지한다 — 목적지를 잘라내면 엉뚱한 지역이 나온다")
    void fallbackKeepsDestinationPrefixWithoutAnchor() {
        when(placeQueryService.searchPlaces("부산 실내 관광지 아이", null, null)).thenReturn(List.of());
        when(placeQueryService.searchPlaces("부산 실내관광지아이", null, null)).thenReturn(List.of());
        when(placeQueryService.searchPlaces("부산 아이", null, null)).thenReturn(List.of());
        when(placeQueryService.searchPlaces("부산 관광지", null, null)).thenReturn(List.of(place("3", "태종대")));

        assertThat(busanTool(new CandidateCollector()).searchPlaces("실내 관광지 아이", null))
                .extracting(PlaceSearchSummary::name).containsExactly("태종대");

        ArgumentCaptor<String> queries = ArgumentCaptor.forClass(String.class);
        verify(placeQueryService, times(4)).searchPlaces(queries.capture(), isNull(), isNull());
        assertThat(queries.getAllValues())
                .containsExactly("부산 실내 관광지 아이", "부산 실내관광지아이", "부산 아이", "부산 관광지");
    }

    @Test
    @DisplayName("기준 장소가 있으면 폴백 질의에도 목적지를 붙이지 않는다 — 좌표가 이미 범위를 좁힌다")
    void anchoredFallbackKeepsQueryUnprefixed() {
        Block onBoard = Block.builder()
                .startOffsetMinutes(0).orderKey("a0").name("해운대해수욕장")
                .category(BlockCategory.SPOT).durationMin(60).budget(0)
                .lat(java.math.BigDecimal.valueOf(35.1587)).lng(java.math.BigDecimal.valueOf(129.1604))
                .source(BlockSource.KAKAO)
                .build();
        when(placeQueryService.searchPlaces("조용한 카페", 35.1587, 129.1604)).thenReturn(List.of());
        when(placeQueryService.searchPlaces("조용한카페", 35.1587, 129.1604)).thenReturn(List.of());
        when(placeQueryService.searchPlaces("카페", 35.1587, 129.1604)).thenReturn(List.of(place("4", "해운대 카페")));
        KakaoPlaceSearchTool tool = new KakaoPlaceSearchTool("부산", placeQueryService, new CandidateCollector(),
                new KakaoPlaceCoordinateResolver(placeQueryService, () -> List.of(onBoard)));

        assertThat(tool.searchPlaces("조용한 카페", "해운대해수욕장"))
                .extracting(PlaceSearchSummary::name).containsExactly("해운대 카페");
        verify(placeQueryService, never()).searchPlaces(eq("부산 카페"), any(), any());
        verify(placeQueryService, never()).searchPlaces(eq("부산 조용한카페"), any(), any());
    }

    @Test
    @DisplayName("폴백까지 전부 0건이면 예전처럼 빈 목록이다 — 상한 3회를 넘겨 더 때리지 않는다")
    void allEmptyStillReturnsEmptyListWithinRetryCap() {
        when(placeQueryService.searchPlaces(anyString(), any(), any())).thenReturn(List.of());

        assertThat(busanTool(new CandidateCollector()).searchPlaces("사진 촬영 명소", null)).isEmpty();
        verify(placeQueryService).searchPlaces("부산 사진 촬영 명소", null, null);
        verify(placeQueryService).searchPlaces("부산 사진촬영명소", null, null);
        verify(placeQueryService).searchPlaces("부산 명소", null, null);
        verify(placeQueryService).searchPlaces("부산 촬영", null, null);
        verify(placeQueryService, times(4)).searchPlaces(anyString(), any(), any());
    }

    @Test
    @DisplayName("단일 토큰 검색어는 0건이어도 재시도하지 않는다 — 축약할 것이 없다")
    void singleTokenKeywordIsNotRetried() {
        when(placeQueryService.searchPlaces("부산 편의점", null, null)).thenReturn(List.of());

        assertThat(busanTool(new CandidateCollector()).searchPlaces("편의점", null)).isEmpty();
        verify(placeQueryService, times(1)).searchPlaces(anyString(), any(), any());
    }

    @Test
    @DisplayName("검색이 예외를 던지면 폴백을 타지 않는다 — 0건과 실패는 다른 신호다")
    void exceptionIsNotRetried() {
        when(placeQueryService.searchPlaces(anyString(), any(), any())).thenThrow(new RuntimeException("kakao down"));

        assertThatThrownBy(() -> busanTool(new CandidateCollector()).searchPlaces("사진 촬영 명소", null))
                .isInstanceOf(IllegalStateException.class);
        verify(placeQueryService, times(1)).searchPlaces(anyString(), any(), any());
    }
}
