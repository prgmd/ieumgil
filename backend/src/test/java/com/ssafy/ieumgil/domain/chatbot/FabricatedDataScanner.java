package com.ssafy.ieumgil.domain.chatbot;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 답변에서 <b>없는 수치·데이터를 사실처럼 말한 표현</b>을 찾아 사람이 읽을 줄로 만든다.
 *
 * <p>{@link ChatbotDemoScenarioLiveTest}가 트랜스크립트에 남기는 신호이며, 게이트가 아니다.
 * 그래서 오탐이 곧 손해다 — 허용된 말을 계속 깃발 세우면 읽는 사람이 스캔 결과 자체를 무시한다.
 * live 테스트 안에 있던 로직을 여기로 빼서 단위 테스트로 고정한다.
 */
final class FabricatedDataScanner {

    /**
     * 없는 수치·데이터를 사실처럼 말하는 표현 — 나오면 안 되는 말이다.
     *
     * <p>정책의 나머지 절반인 <b>단정</b>("이 카페는 조용합니다")은 순수 문자열 스캔으로
     * 짐작("조용한 편일 수 있어요")과 구분할 수 없다. 그래서 여기서는 수치·데이터 패턴만
     * 잡고, 단정 여부는 <b>사람이 트랜스크립트를 읽어</b> 판단한다.
     *
     * <p>짐작임을 밝힌 추론과 널리 알려진 상식("유명하다"·"인기가 많다")은 정책상 허용이라
     * 더 이상 잡지 않는다.
     *
     * <p>{@code 평점}·{@code 별점}은 앞에 한글 음절이 붙으면 매치하지 않는다 —
     * 카카오 상호명 {@code CU 장산햇별점} 안의 부분 문자열을 위반으로 잡은 적이 있다.
     */
    private static final Pattern FABRICATED_DATA_PATTERN =
            Pattern.compile("(?<![가-힣])(?:평점|별점)|리뷰가 좋|리뷰\\s*수|리뷰\\s*\\d|\\d+\\s*위");

    /**
     * 능력 부정 — 수치를 <b>댈 수 없다</b>고 밝히는 말이라 위반이 아니라 원하는 동작이다.
     * ("평점이나 실제 후기 수치를 확인할 수 없어서 …")
     */
    private static final List<String> CAPABILITY_NEGATIONS = List.of(
            "확인할 수 없", "알 수 없", "제공하지 않", "드릴 수 없", "가지고 있지 않", "없어서");

    /** 부정 표현을 찾을 때 매치 <b>뒤로만</b> 보는 최대 길이 — 문장 전체를 보면 단정을 놓친다 */
    private static final int NEGATION_WINDOW = 40;

    private FabricatedDataScanner() {
    }

    /** 문장별로 <b>억제되지 않은 첫 매치</b> 하나를 {@code - [매치] 문장} 형태로 돌려준다 */
    static List<String> scan(String reply) {
        if (reply == null) {
            return List.of();
        }
        List<String> hits = new ArrayList<>();
        for (String sentence : reply.split("(?<=[.!?])\\s+|\\n+")) {
            Matcher matcher = FABRICATED_DATA_PATTERN.matcher(sentence);
            while (matcher.find()) {
                if (isCapabilityDisclaimer(sentence, matcher.end())) {
                    continue;
                }
                hits.add("- [%s] %s".formatted(matcher.group(), sentence.trim()));
                break;
            }
        }
        return hits;
    }

    /**
     * 매치 뒤 좁은 창에 능력 부정이 오면 억제한다.
     *
     * <p>단, 부정보다 앞에 이미 숫자가 나왔다면 수치를 <b>단정한 뒤</b> 덧붙인 말이므로
     * 억제하지 않는다 — "평점 4.5인데 리뷰는 없어서 아쉽네요"는 그대로 보고돼야 한다.
     */
    private static boolean isCapabilityDisclaimer(String sentence, int matchEnd) {
        String ahead = sentence.substring(matchEnd, Math.min(sentence.length(), matchEnd + NEGATION_WINDOW));
        for (String negation : CAPABILITY_NEGATIONS) {
            int at = ahead.indexOf(negation);
            if (at >= 0 && ahead.substring(0, at).chars().noneMatch(Character::isDigit)) {
                return true;
            }
        }
        return false;
    }
}
