package com.ssafy.ieumgil.domain.chatbot.tool;

import com.ssafy.ieumgil.domain.place.dto.PlaceResDTO;

import java.util.List;
import java.util.function.Function;

/**
 * 일반 모드({@link KakaoPlaceSearchTool})와 지도 모드({@link ViewportPlaceSearchTool}) 장소검색 tool의
 * 공통 조각. 두 tool은 검색 범위(목적지 접두 vs 뷰포트)만 다를 뿐,
 * <b>0건일 때의 검색어 폴백 루프</b>와 <b>내부 오류 시 모델에 올리는 실패 메시지</b>가 동일하다.
 */
final class PlaceSearchSupport {

    /**
     * 검색 실패를 빈 목록으로 삼키면 모델이 "이 지역엔 없어요"로 오인한다. 이 메시지를 예외로 올려
     * Spring AI가 tool 결과로 모델에 전달하게 한다(호출 abort는 아님).
     */
    static final String SEARCH_FAILURE_MESSAGE =
            "Place search failed due to an internal error. Do not claim there are no matching places; tell the user the search could not be completed and to try again.";

    private PlaceSearchSupport() {
    }

    /** 폴백 재시도가 성공했을 때 호출된다 — 로그 문구가 tool마다 달라 콜백으로 뺀다. */
    @FunctionalInterface
    interface FallbackHit {
        void onHit(String keyword, String candidate, int count);
    }

    /**
     * 0건이면 검색어를 바꿔 재시도한다 — 카카오는 수식어가 붙은 서술구에 0건을 준다.
     * 후보는 붙여쓰기 → 오른쪽 단일 토큰 순이다({@link KeywordFallback}).
     *
     * <p>첫 비-empty 결과에서 멈추고, 재시도까지 전부 0건이면 예전처럼 빈 목록이다. 예외는 폴백
     * 대상이 아니다(0건과 실패는 다른 신호이므로 호출부 catch가 그대로 받는다).
     *
     * @param search 검색어 하나로 장소를 찾는다. 범위 한정(목적지 접두·좌표·뷰포트)은 호출부가 담는다.
     * @param onHit  폴백 재시도가 성공했을 때의 로그 훅.
     */
    static List<PlaceResDTO.Place> searchWithKeywordFallback(
            String keyword,
            Function<String, List<PlaceResDTO.Place>> search,
            FallbackHit onHit) {
        List<PlaceResDTO.Place> found = search.apply(keyword);
        if (!found.isEmpty()) {
            return found;
        }
        for (String candidate : KeywordFallback.candidatesFor(keyword)) {
            List<PlaceResDTO.Place> retried = search.apply(candidate);
            if (!retried.isEmpty()) {
                onHit.onHit(keyword, candidate, retried.size());
                return retried;
            }
        }
        return found;
    }
}
