package com.ssafy.ieumgil.domain.chatbot.tool;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 카카오는 장소 종류어 한 개만 제대로 받는다 — 수식어가 붙은 서술구는 0건이다.
 * 붙여쓰기는 의도를 보존한 채, 단일 토큰 축약은 의도를 버려서 결과를 얻으므로 붙여쓰기가 먼저다.
 * 축약 후보는 한국어 명사구의 핵심어 후치 성질을 따라 오른쪽부터 뽑는다.
 */
class KeywordFallbackTest {

    @Test
    @DisplayName("붙여쓰기가 1번 후보다 — 의도를 버리는 축약보다 의도를 지키는 쪽을 먼저 시도한다")
    void concatenatedKeywordComesFirst() {
        // 순서가 뒤집히면 '키즈 카페'가 일반 카페로 떨어진다. containsExactly로 순서를 고정한다.
        assertThat(KeywordFallback.candidatesFor("키즈 카페"))
                .containsExactly("키즈카페", "카페", "키즈");
    }

    @Test
    @DisplayName("붙여쓰기 다음은 오른쪽 토큰부터 단일어로 축약한다 — 한국어는 핵심어가 뒤에 온다")
    void thenPicksSingleTokensFromRightToLeft() {
        assertThat(KeywordFallback.candidatesFor("사진 촬영 명소"))
                .containsExactly("사진촬영명소", "명소", "촬영");
        assertThat(KeywordFallback.candidatesFor("실내 관광지 아이"))
                .containsExactly("실내관광지아이", "아이", "관광지");
    }

    @Test
    @DisplayName("단일 토큰은 붙일 것도 자를 것도 없으므로 재시도하지 않는다")
    void singleTokenHasNothingToTry() {
        assertThat(KeywordFallback.candidatesFor("편의점")).isEmpty();
    }

    @Test
    @DisplayName("null·공백은 빈 목록이다 — 빈 문자열로 카카오를 때리지 않는다")
    void nullOrBlankYieldsNoCandidates() {
        assertThat(KeywordFallback.candidatesFor(null)).isEmpty();
        assertThat(KeywordFallback.candidatesFor("  ")).isEmpty();
    }

    @Test
    @DisplayName("같은 토큰이 반복되면 한 번만 시도한다 — 중복 카카오 호출 방지")
    void deduplicatesRepeatedTokens() {
        assertThat(KeywordFallback.candidatesFor("카페 카페")).containsExactly("카페카페", "카페");
    }

    @Test
    @DisplayName("토큰이 많아도 후보는 3개까지다 — 카카오 쿼터와 지연 때문에 상한을 둔다")
    void capsCandidatesAtThree() {
        assertThat(KeywordFallback.candidatesFor("a b c d")).containsExactly("abcd", "d", "c");
    }

    @Test
    @DisplayName("토큰 사이 공백이 여러 칸이어도 빈 후보가 생기지 않는다")
    void ignoresExtraWhitespaceBetweenTokens() {
        assertThat(KeywordFallback.candidatesFor("  부산   실내  테마파크 "))
                .containsExactly("부산실내테마파크", "테마파크", "실내");
    }
}
