package com.ssafy.ieumgil.domain.chatbot.tool;

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

        KakaoPlaceSearchTool tool = new KakaoPlaceSearchTool("제주도", placeQueryService, new CandidateCollector());

        List<PlaceSearchSummary> result = tool.searchPlaces("흑돼지");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).name()).isEqualTo("제주 흑돼지 맛집");
        assertThat(result.get(0).url()).isEqualTo("https://place.map.kakao.com/12345");
    }

    @Test
    void returnsEmptyListWhenNoResults() {
        when(placeQueryService.searchPlaces(eq("제주도 존재하지않는것"), isNull(), isNull()))
                .thenReturn(List.of());

        KakaoPlaceSearchTool tool = new KakaoPlaceSearchTool("제주도", placeQueryService, new CandidateCollector());

        assertThat(tool.searchPlaces("존재하지않는것")).isEmpty();
    }

    @Test
    void returnsEmptyListInsteadOfThrowingWhenSearchFails() {
        when(placeQueryService.searchPlaces(any(), any(), any())).thenThrow(new RuntimeException("kakao down"));

        KakaoPlaceSearchTool tool = new KakaoPlaceSearchTool("제주도", placeQueryService, new CandidateCollector());

        assertThat(tool.searchPlaces("흑돼지")).isEmpty();
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
        KakaoPlaceSearchTool tool = new KakaoPlaceSearchTool("제주도", placeQueryService, collector);

        tool.searchPlaces("카페");

        assertThat(collector.candidates()).hasSize(1);
        assertThat(collector.candidates().get(0).placeId()).isEqualTo("77");
    }
}
