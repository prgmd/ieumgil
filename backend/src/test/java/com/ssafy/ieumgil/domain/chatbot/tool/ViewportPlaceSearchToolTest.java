package com.ssafy.ieumgil.domain.chatbot.tool;

import com.ssafy.ieumgil.domain.chatbot.dto.ChatbotReqDTO;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
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

    @Test
    @DisplayName("뷰포트 범위로 검색하고 목적지 문자열은 붙이지 않는다 — 보이는 범위가 곧 범위다")
    void searchesWithinViewportWithoutPrefixingDestination() {
        when(placeQueryService.searchPlacesInRect("카페", 33.44, 126.93, 33.47, 126.95))
                .thenReturn(List.of(place("1", "스타벅스 성산일출봉점")));
        ViewportPlaceSearchTool tool = new ViewportPlaceSearchTool(
                VIEWPORT, placeQueryService, new CandidateCollector());

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
        ViewportPlaceSearchTool tool = new ViewportPlaceSearchTool(VIEWPORT, placeQueryService, collector);

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
                VIEWPORT, placeQueryService, new CandidateCollector());

        assertThat(tool.searchPlacesInView("카페")).isEmpty();
    }

    @Test
    @DisplayName("카카오 호출이 실패해도 대화는 계속된다 — 빈 목록으로 떨어뜨린다")
    void kakaoFailureDegradesToEmptyList() {
        when(placeQueryService.searchPlacesInRect(anyString(), anyDouble(), anyDouble(), anyDouble(), anyDouble()))
                .thenThrow(new PlaceException(PlaceErrorCode.KAKAO_API_CALL_FAILED));
        ViewportPlaceSearchTool tool = new ViewportPlaceSearchTool(
                VIEWPORT, placeQueryService, new CandidateCollector());

        assertThat(tool.searchPlacesInView("카페")).isEmpty();
    }
}
