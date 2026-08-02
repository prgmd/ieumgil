package com.ssafy.ieumgil.domain.chatbot.tool;

import com.ssafy.ieumgil.domain.block.entity.Block;
import com.ssafy.ieumgil.domain.block.entity.BlockCategory;
import com.ssafy.ieumgil.domain.block.entity.BlockSource;
import com.ssafy.ieumgil.domain.place.dto.PlaceResDTO;
import com.ssafy.ieumgil.domain.place.service.PlaceQueryService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
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
    void returnsEmptyListInsteadOfThrowingWhenSearchFails() {
        when(placeQueryService.searchPlaces(any(), any(), any())).thenThrow(new RuntimeException("kakao down"));

        KakaoPlaceSearchTool tool = new KakaoPlaceSearchTool("제주도", placeQueryService, new CandidateCollector(), new KakaoPlaceCoordinateResolver(placeQueryService));

        assertThat(tool.searchPlaces("흑돼지", null)).isEmpty();
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
                .dayNo(1).orderKey("a0").name("성산일출봉")
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
}
