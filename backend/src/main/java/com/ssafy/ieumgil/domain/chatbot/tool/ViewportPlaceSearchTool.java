package com.ssafy.ieumgil.domain.chatbot.tool;

import com.ssafy.ieumgil.domain.chatbot.dto.ChatbotReqDTO;
import com.ssafy.ieumgil.domain.place.dto.PlaceResDTO;
import com.ssafy.ieumgil.domain.place.service.PlaceQueryService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;

import java.util.List;

/**
 * 지도 기반 추천 모드의 장소검색 tool (BOT-03).
 *
 * <p>일반 모드의 {@link KakaoPlaceSearchTool}과 다른 점은 검색어에 프로젝트 목적지를
 * 붙이지 않고 사용자가 보고 있는 지도 범위로 결과를 한정한다는 것이다. "보이는 범위"가
 * 곧 검색 범위이므로 목적지 문자열을 덧붙이면 오히려 범위와 어긋난다.
 */
@Slf4j
public class ViewportPlaceSearchTool {

    private final ChatbotReqDTO.MapContext viewport;
    private final PlaceQueryService placeQueryService;
    private final CandidateCollector candidateCollector;
    private final PlaceRanker.RankingContext rankingContext;

    public ViewportPlaceSearchTool(ChatbotReqDTO.MapContext viewport, PlaceQueryService placeQueryService,
                                   CandidateCollector candidateCollector,
                                   PlaceRanker.RankingContext rankingContext) {
        this.viewport = viewport;
        this.placeQueryService = placeQueryService;
        this.candidateCollector = candidateCollector;
        this.rankingContext = rankingContext;
    }

    @Tool(description = """
            Call this to find places inside the map area the user is currently looking at.
            Derive the search keyword from what the user asked (for example "quiet cafe" -> "cafe"). Do not add a region or city name — the visible map area already defines where to search.
            Returns places within that area only. Each result includes a link to view the place directly, so include the link in your answer as well.
            If it returns nothing, tell the user there is nothing matching in the current view and suggest moving or zooming out the map. Never fill the gap with places you know from memory — they may be far outside the visible area.
            """)
    public List<PlaceSearchSummary> searchPlacesInView(String keyword) {
        try {
            List<PlaceResDTO.Place> found = searchWithKeywordFallback(keyword);
            // 카카오 순서를 기준선으로 두고 근거 있는 신호(프랜차이즈·중복·거리)만 페널티로 얹는다.
            // 상한까지 자른 뒤에 수집하므로, 모델이 보는 것과 카드가 같은 집합이 된다.
            List<PlaceResDTO.Place> places = rankOrKeepOrder(found).stream()
                    .limit(PlaceQueryService.LLM_CANDIDATE_LIMIT)
                    .toList();
            // 모델에는 좌표를 넘기지 않으므로(요약만), 블록 생성용 원본은 수집기가 따로 보관한다
            places.forEach(candidateCollector::addPlace);
            // 이유도 서버가 계산해 넘긴다 — 모델이 "리뷰가 좋아서" 같은 없는 근거를 지어내지 않도록
            return summarize(places);
        } catch (RuntimeException e) {
            // 실패를 빈 목록으로 삼키면 모델이 "이 지역엔 없어요"로 오인한다. 실패 신호를
            // 그대로 올려 Spring AI가 tool 결과로 모델에 전달하게 한다(호출 abort는 아님).
            log.warn("viewport place search tool call failed for keyword={}", keyword, e);
            throw new IllegalStateException(
                    "Place search failed due to an internal error. Do not claim there are no matching places; tell the user the search could not be completed and to try again.", e);
        }
    }

    /**
     * 0건이면 검색어를 바꿔 재시도한다 — 카카오는 수식어가 붙은 서술구에 0건을 준다.
     * 후보는 붙여쓰기 → 오른쪽 단일 토큰 순이다({@link KeywordFallback}).
     *
     * <p>첫 비-empty 결과에서 멈추고, 재시도까지 전부 0건이면 예전처럼 빈 목록이다.
     * 예외는 폴백 대상이 아니다(0건과 실패는 다른 신호이므로 호출부 catch가 그대로 받는다).
     */
    private List<PlaceResDTO.Place> searchWithKeywordFallback(String keyword) {
        List<PlaceResDTO.Place> found = searchInView(keyword);
        if (!found.isEmpty()) {
            return found;
        }
        for (String candidate : KeywordFallback.candidatesFor(keyword)) {
            List<PlaceResDTO.Place> retried = searchInView(candidate);
            if (!retried.isEmpty()) {
                // 운영에서 이 폴백이 얼마나 자주 도는지 봐야 한다
                log.info("뷰포트 검색 0건 — 검색어 폴백 성공: '{}' -> '{}' ({}건)",
                        keyword, candidate, retried.size());
                return retried;
            }
        }
        return found;
    }

    private List<PlaceResDTO.Place> searchInView(String keyword) {
        return placeQueryService.searchPlacesInRect(
                keyword, viewport.swLat(), viewport.swLng(), viewport.neLat(), viewport.neLng());
    }

    /**
     * 재정렬은 있으면 좋은 것이고 카드는 이 기능의 존재 이유다.
     *
     * <p>그래서 재정렬이 어떤 이유로든 실패하면 카카오가 준 순서를 그대로 쓴다 — 순위를 잃는 것은
     * 감수할 수 있지만 카드를 잃으면 사용자는 아무것도 일정에 담을 수 없다. 다만 조용히 넘어가면
     * "왜 순서가 안 바뀌지"를 영영 모르게 되므로 경고는 반드시 남긴다.
     */
    /**
     * 추천 이유도 재정렬과 같은 판단이다 — 있으면 좋지만 카드보다 뒤다.
     *
     * <p>이유 계산에서 예외가 새면 tool 호출 전체가 실패해 카드가 0건이 된다. 그래서 여기서
     * 막고 이유 없이 내보낸다. 이유가 없으면 프롬프트가 모델에게 "억지 이유를 붙이지 말고
     * 담백하게 소개하라"고 이미 지시해 두었으므로, 없는 근거로 이어지지 않는다.
     */
    private List<PlaceSearchSummary> summarize(List<PlaceResDTO.Place> places) {
        try {
            return places.stream()
                    .map(p -> PlaceSearchSummary.from(p, PlaceRanker.reasonsFor(p, rankingContext)))
                    .toList();
        } catch (RuntimeException e) {
            log.warn("추천 이유 계산 실패 — 이유 없이 내보낸다 (장소 {}건)", places.size(), e);
            return places.stream().map(PlaceSearchSummary::from).toList();
        }
    }

    private List<PlaceResDTO.Place> rankOrKeepOrder(List<PlaceResDTO.Place> found) {
        try {
            return PlaceRanker.rank(found, rankingContext);
        } catch (RuntimeException e) {
            log.warn("장소 재정렬 실패 — 카카오 순서를 그대로 쓴다 (검색 결과 {}건)", found.size(), e);
            return found;
        }
    }
}
