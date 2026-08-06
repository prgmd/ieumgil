package com.ssafy.ieumgil.domain.chatbot.tool;

import com.ssafy.ieumgil.domain.place.dto.PlaceResDTO;
import com.ssafy.ieumgil.domain.place.service.PlaceQueryService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.util.List;
import java.util.Optional;

@Slf4j
public class KakaoPlaceSearchTool {

    private final String destination;
    private final PlaceQueryService placeQueryService;
    private final CandidateCollector candidateCollector;
    private final KakaoPlaceCoordinateResolver resolver;

    public KakaoPlaceSearchTool(String destination, PlaceQueryService placeQueryService,
                                CandidateCollector candidateCollector,
                                KakaoPlaceCoordinateResolver resolver) {
        this.destination = destination;
        this.placeQueryService = placeQueryService;
        this.candidateCollector = candidateCollector;
        this.resolver = resolver;
    }

    @Tool(description = """
            Call this when the user asks for specific places to recommend, such as restaurants, cafes, lodging, or tourist attractions.
            For festival, event, or things-to-do recommendations, use the regional festival recommendation tool instead of this one; this tool only covers places that operate year-round.
            keyword: only what kind of place to look for (for example "bibimbap restaurant", "quiet cafe"). Do not include the region or city name — the current project's destination is prepended automatically, so adding it yourself duplicates it and degrades the search.
            nearPlaceName: optional. Pass a place name to search around that spot instead of the destination as a whole, and results come back nearest-first. Use it when the user anchors the request to somewhere — "near the 2nd stop on day 2", "somewhere close to the hotel". Pass the block's name as it appears in the itinerary; leave this out when the user just asks about the destination in general.
            Returns matching places. Each result includes a link to view the place directly, so include the link in your answer as well.
            Describe each place using only the returned fields. Do not add reputation claims such as "the most famous" or "a long-established restaurant" — that information is not in the result.
            """)
    public List<PlaceSearchSummary> searchPlaces(String keyword, @ToolParam(required = false) String nearPlaceName) {
        try {
            // 기준 장소가 있으면 그 좌표 주변을 거리순으로 찾는다. 좌표가 범위를 좁히므로
            // 목적지 접두사는 붙이지 않는다 — 붙이면 검색어 특이성만 떨어진다.
            // 좌표는 보드 우선으로 해석되므로 일정에 올려둔 블록이면 카카오 재검색이 없다.
            Optional<PlaceResDTO.Place> anchor = resolveAnchor(nearPlaceName);
            String query = anchor.isPresent() ? keyword : destination + " " + keyword;
            // searchPlaces는 사용자 검색 패널 상한(15)으로 오므로, 여기서 챗봇 상한으로
            // 다시 자른다 — 뷰포트 tool과 같은 CHATBOT_SEARCH_LIMIT을 공유해 LLM 프롬프트
            // 토큰이 3배로 뛰지 않게 한다.
            List<PlaceResDTO.Place> places = placeQueryService.searchPlaces(
                    query,
                    anchor.map(PlaceResDTO.Place::lat).orElse(null),
                    anchor.map(PlaceResDTO.Place::lng).orElse(null))
                    .stream()
                    .limit(PlaceQueryService.CHATBOT_SEARCH_LIMIT)
                    .toList();
            // 모델에는 좌표를 넘기지 않으므로(요약만), 블록 생성용 원본은 수집기가 따로 보관한다
            places.forEach(candidateCollector::addPlace);
            return places.stream()
                    .map(PlaceSearchSummary::from)
                    .toList();
        } catch (RuntimeException e) {
            // 실패를 빈 목록으로 삼키면 모델이 "이 지역엔 없어요"로 오인한다. 실패 신호를
            // 그대로 올려 Spring AI가 tool 결과로 모델에 전달하게 한다(호출 abort는 아님).
            log.warn("place search tool call failed for keyword={}", keyword, e);
            throw new IllegalStateException(
                    "Place search failed due to an internal error. Do not claim there are no matching places; tell the user the search could not be completed and to try again.", e);
        }
    }

    /** 기준 장소를 좌표로 바꾼다. 못 구하면 빈 Optional — 호출부가 목적지 기준 검색으로 떨어진다. */
    private Optional<PlaceResDTO.Place> resolveAnchor(String nearPlaceName) {
        if (nearPlaceName == null || nearPlaceName.isBlank()) {
            return Optional.empty();
        }
        return resolver.resolve(destination, nearPlaceName);
    }
}
