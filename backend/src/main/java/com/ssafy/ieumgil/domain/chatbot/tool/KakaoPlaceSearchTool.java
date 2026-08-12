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
            Call this when the user asks you to find or recommend specific places, businesses, or venues — restaurants, cafes, lodging, tourist attractions, and also service businesses like car-rental agencies, pharmacies, or shops. Each result already includes a link, so use this tool when the user asks for a place or business "with a link" too.
            For festival, event, or things-to-do recommendations, use the regional festival recommendation tool instead of this one; this tool only covers places that operate year-round.
            keyword: only what kind of place to look for (for example "bibimbap restaurant", "quiet cafe"). Do not include the region or city name — the current project's destination is prepended automatically, so adding it yourself duplicates it and degrades the search.
            nearPlaceName: optional. Pass a place name to search around that spot instead of the destination as a whole, and results come back nearest-first. Use it when the user anchors the request to somewhere — "near the 2nd stop on day 2", "somewhere close to the hotel". Pass the block's name as it appears in the itinerary; leave this out when the user just asks about the destination in general.
            Returns matching places. Each result includes a link to view the place directly, so include the link in your answer as well.
            Describe each place using only the returned fields. Do not add reputation claims such as "the most famous" or "a long-established restaurant" — that information is not in the result.
            """)
    public List<PlaceSearchSummary> searchPlaces(String keyword, @ToolParam(required = false) String nearPlaceName) {
        try {
            // 좌표는 보드 우선으로 해석되므로 일정에 올려둔 블록이면 카카오 재검색이 없다.
            Optional<PlaceResDTO.Place> anchor = resolveAnchor(nearPlaceName);
            // searchPlaces는 사용자 검색 패널 상한(15)으로 오므로, 여기서 일반 모드 상한으로
            // 다시 자른다 — 그대로 실으면 LLM 프롬프트 토큰이 3배로 뛴다. 지도 모드는 재정렬을
            // 위해 더 많이 받아 자기 상한(LLM_CANDIDATE_LIMIT)으로 자르므로 이 값과 무관하다.
            List<PlaceResDTO.Place> places = searchWithKeywordFallback(keyword, anchor)
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
            throw new IllegalStateException(PlaceSearchSupport.SEARCH_FAILURE_MESSAGE, e);
        }
    }

    /**
     * 축약 대상은 {@code keyword} 뿐이고 목적지 접두사 규칙은 그대로다 — 폴백 질의도 앵커가 없으면
     * {@code 목적지 + " " + 축약키워드}다. 목적지 문자열 자체를 잘라내면 엉뚱한 지역이 나온다.
     * 공통 폴백 루프는 {@link PlaceSearchSupport}가 담고, 여기서는 접두·좌표만 씌운다.
     */
    private List<PlaceResDTO.Place> searchWithKeywordFallback(String keyword, Optional<PlaceResDTO.Place> anchor) {
        Double lat = anchor.map(PlaceResDTO.Place::lat).orElse(null);
        Double lng = anchor.map(PlaceResDTO.Place::lng).orElse(null);
        boolean anchored = anchor.isPresent();

        return PlaceSearchSupport.searchWithKeywordFallback(
                keyword,
                k -> placeQueryService.searchPlaces(queryFor(k, anchored), lat, lng),
                // 운영에서 이 폴백이 얼마나 자주 도는지 봐야 한다
                (kw, candidate, count) -> log.info("장소 검색 0건 — 검색어 폴백 성공: '{}' -> '{}' ({}건)",
                        queryFor(kw, anchored), queryFor(candidate, anchored), count));
    }

    /**
     * 기준 장소가 있으면 그 좌표 주변을 거리순으로 찾는다. 좌표가 범위를 좁히므로
     * 목적지 접두사는 붙이지 않는다 — 붙이면 검색어 특이성만 떨어진다.
     */
    private String queryFor(String keyword, boolean anchored) {
        return anchored ? keyword : destination + " " + keyword;
    }

    /** 기준 장소를 좌표로 바꾼다. 못 구하면 빈 Optional — 호출부가 목적지 기준 검색으로 떨어진다. */
    private Optional<PlaceResDTO.Place> resolveAnchor(String nearPlaceName) {
        if (nearPlaceName == null || nearPlaceName.isBlank()) {
            return Optional.empty();
        }
        return resolver.resolve(destination, nearPlaceName);
    }
}
