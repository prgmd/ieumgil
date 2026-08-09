package com.ssafy.ieumgil.domain.chatbot;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link FabricatedDataScanner}의 오탐/정탐을 실제 트랜스크립트에서 나온 문장으로 고정한다.
 *
 * <p>{@code live} 태그를 달지 않는다 — 일반 테스트 스위트에서 매번 돌아야 회귀를 막는다.
 */
class FabricatedDataScannerTest {

    @Test
    @DisplayName("상호명 안의 '별점'은 잡지 않는다")
    void ignoresTermInsidePlaceName() {
        // 실제 트랜스크립트: 카카오 상호명 "CU 장산햇별점"이 위반으로 찍혔다. 모델은 잘못한 게 없다.
        List<String> hits = FabricatedDataScanner.scan(
                "- **CU 장산햇별점** [링크](https://place.map.kakao.com/11695217) - 세실로69번길 5");

        assertThat(hits).isEmpty();
    }

    @Test
    @DisplayName("수치를 댈 수 없다고 밝히는 문장은 잡지 않는다")
    void ignoresCapabilityDisclaimer() {
        // 실제 트랜스크립트: "평점이나 실제 후기 수치를 확인할 수 없어서" — 원하는 동작인데 위반으로 찍혔다.
        List<String> hits = FabricatedDataScanner.scan(
                "다만 저는 앱 내에서 평점이나 실제 후기 수치를 확인할 수 없어서, 웹에서 확인해 드릴 수 있습니다.");

        assertThat(hits).isEmpty();
    }

    @Test
    @DisplayName("없는 수치를 단정하면 그대로 보고한다")
    void reportsAssertedFigure() {
        // 오탐을 없앤 뒤에도 진짜 위반은 잡혀야 한다 — 스캔의 존재 이유다.
        List<String> hits = FabricatedDataScanner.scan("이 카페는 평점 4.5에 리뷰 200개가 넘어요.");

        assertThat(hits).containsExactly("- [평점] 이 카페는 평점 4.5에 리뷰 200개가 넘어요.");
    }

    @Test
    @DisplayName("수치를 단정한 뒤에 나온 부정 표현은 억제하지 않는다")
    void negationAfterAssertedFigureDoesNotSuppress() {
        // 억제 창을 문장 전체로 넓히면 이 문장이 조용히 사라진다 — 수치가 부정보다 먼저 나왔다.
        List<String> hits = FabricatedDataScanner.scan("평점 4.5인데 리뷰는 없어서 아쉽네요.");

        assertThat(hits).containsExactly("- [평점] 평점 4.5인데 리뷰는 없어서 아쉽네요.");
    }

    @Test
    @DisplayName("문장 맨 앞의 '별점'은 lookbehind에 걸리지 않는다")
    void matchesTermAtSentenceStart() {
        // 상호명 오탐을 막는 lookbehind가 정상 매치까지 죽이면 안 된다.
        List<String> hits = FabricatedDataScanner.scan("별점 4.5 수준으로 좋습니다.");

        assertThat(hits).containsExactly("- [별점] 별점 4.5 수준으로 좋습니다.");
    }

    @Test
    @DisplayName("한 문장에서 앞 매치가 억제돼도 뒤의 진짜 매치는 보고한다")
    void reportsLaterMatchWhenFirstIsSuppressed() {
        // 문장당 첫 매치만 보던 예전 방식이면 억제된 매치에 가려 진짜 위반이 통째로 묻힌다.
        List<String> hits = FabricatedDataScanner.scan("평점은 확인할 수 없지만 리뷰 200개가 넘는 곳이에요.");

        assertThat(hits).containsExactly("- [리뷰 2] 평점은 확인할 수 없지만 리뷰 200개가 넘는 곳이에요.");
    }
}
