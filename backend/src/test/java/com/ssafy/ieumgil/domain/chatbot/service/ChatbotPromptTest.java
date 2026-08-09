package com.ssafy.ieumgil.domain.chatbot.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 실제 모델 응답이 아니라 <b>프롬프트 문구 자체</b>를 검증한다.
 *
 * <p>정책의 경계는 "그 주제를 말했는가"가 아니라 <b>단정이냐 추론이냐</b>다. 없는 수치를
 * 사실처럼 말하는 것과 특정 장소의 특징을 단정하는 것은 금지지만, 짐작임을 밝힌 추론과
 * 널리 알려진 상식("유명하다"·"인기가 많다")은 허용이다. 금지가 넓어져 모델이 밋밋해지는
 * 회귀와, 금지 문구가 조용히 빠지는 회귀를 양쪽 다 여기서 잡는다.
 */
class ChatbotPromptTest {

    @Test
    @DisplayName("SYSTEM 프롬프트는 근거 없는 수치(평점·리뷰 수)를 사실처럼 말하는 것을 금지한다")
    void systemPromptForbidsFabricatedNumbers() {
        // 어휘("평점" 등)만 있으면 "평점을 말해도 됩니다" 같은 허용문에도 통과한다 —
        // 실제로 금지하는 서술까지 같이 있어야 이 문구가 허용이 아니라 금지임을 확인할 수 있다.
        assertThat(ChatbotPrompt.SYSTEM).contains("평점");
        assertThat(ChatbotPrompt.SYSTEM).contains("리뷰 수");
        assertThat(ChatbotPrompt.SYSTEM).contains("우리에게 없는 수치나 데이터를 사실처럼 말하지 마세요");
    }

    @Test
    @DisplayName("SYSTEM 프롬프트는 특정 장소의 확인되지 않은 특징을 단정하는 것도 금지한다")
    void systemPromptForbidsAssertingUnverifiedTraits() {
        assertThat(ChatbotPrompt.SYSTEM).contains("확인되지 않은 특징도 단정하지 마세요");
    }

    @Test
    @DisplayName("SYSTEM 프롬프트는 짐작임을 밝힌 추론을 명시적으로 허용한다 — 과잉 억제 방지")
    void systemPromptPermitsHedgedInference() {
        // 이 허용 문장이 빠지면 모델은 금지를 넓게 읽고 추론 자체를 접는다.
        assertThat(ChatbotPrompt.SYSTEM).contains("짐작임을 드러내며 말해도 됩니다");
    }

    @Test
    @DisplayName("SYSTEM 프롬프트는 짐작 허용 범위를 '장소의 성격'으로 한정하고 '장소 목록'은 제외한다")
    void systemPromptLimitsHedgedInferenceToKnownPlaces() {
        // E2E 회귀: 모델이 도구 없이 태종대·자갈치시장 같은 이름을 나열했다. 허용은 유지하되
        // 무엇이 존재하는지는 도구 결과여야 한다는 한정이 없으면 이 구멍이 다시 열린다.
        assertThat(ChatbotPrompt.SYSTEM).contains("목록 자체는 짐작 대상이 아니라")
                .contains("도구 결과여야 합니다");
    }

    @Test
    @DisplayName("SYSTEM 프롬프트는 일정을 가리키는 말을 되묻지 말고 도구로 풀게 한다")
    void systemPromptResolvesItineraryReferencesWithTools() {
        // 시연 문장 "점심 먹은 데 근처에 카페 있어?"에서 모델이 보드에 점심 블록이 있는데도
        // getCurrentPlan 을 안 부르고 "어디서 드셨는지 알려주시면"으로 되물어 카드가 0건이 됐다.
        // 일반적인 "되묻지 말라"는 이미 있었지만, 일정 표현을 도구로 푼다는 연결이 없었다.
        assertThat(ChatbotPrompt.SYSTEM).contains("일정 안의 무언가를 가리키는 말")
                .contains("되묻지 말고 일정 도구로 확인해")
                .contains("장소 검색 도구에 넘겨");
        // 보드를 읽게 만든 뒤에는 다음 턱에 걸렸다 — 1박2일이라 점심 블록이 둘이라서
        // "1일차냐 2일차냐"를 되물으며 또 0건이 됐다. 다중 후보에서도 먼저 답하게 한다.
        assertThat(ChatbotPrompt.SYSTEM).contains("여러 날에 걸쳐 여럿이면")
                .contains("가장 이른 날 것을");
        // "가장 이른 날"은 짐작이었다. 사용자가 보고 있는 Day 탭이 곧 그 "점심"이므로 서버가
        // [Viewing] 으로 실어 주고, 이 문장이 거기를 먼저 가리킨다(이른 날은 폴백으로 남는다).
        assertThat(ChatbotPrompt.SYSTEM).contains("[Viewing] 의 Day 것을");
    }

    @Test
    @DisplayName("SYSTEM 프롬프트는 장소 추천 요청에 종류를 되묻지 말고 먼저 검색하게 한다")
    void systemPromptSearchesBeforeAskingWhatKindOfPlace() {
        // "부산 가볼 만한 곳 추천해줘"에서 모델이 툴을 하나도 안 부르고 해운대·광안리·용두산공원을
        // 자체 지식으로 나열하며 "어떤 종류에 관심 있으세요?"로 되물어 카드가 0건이 됐다.
        // 앞서 이 규칙을 코스 질의에만 걸었더니 다른 질문 모양에서 그대로 재발했다 —
        // 그래서 "장소를 추천·검색해 달라는 요청" 전체로 넓히고, 대신 문장 수를 줄여
        // "먼저 답하고 나중에 묻는다"는 원칙이 다른 조항에 희석되지 않게 했다.
        assertThat(ChatbotPrompt.SYSTEM).contains("어떤 종류를 원하는지 되묻지 말고")
                .contains("먼저 장소 검색")
                .contains("코스·루트를 물을 때도 같습니다");
    }

    @Test
    @DisplayName("MAP_TAIL 의 수치 규칙은 출처 기준이다 — 확인한 값은 허용, 확인 안 한 값은 금지")
    void mapTailAllowsVerifiedNumbersAndForbidsFabricatedOnes() {
        // MAP 모드에도 web_search 가 붙어 이제 출처가 생겼다. 무조건 금지는 SYSTEM 의 출처 기준
        // 정책과 어긋나므로, 확인 여부로 가르는 문장이 있어야 한다.
        String mapTail = ChatbotPrompt.modeTail(com.ssafy.ieumgil.domain.chatbot.ChatbotMode.MAP);

        assertThat(mapTail).contains("평점")
                .contains("웹 검색으로 확인한 것만")
                .contains("확인하지 않은 수치는 지어내지 마세요");
    }

    @Test
    @DisplayName("MAP_TAIL 은 위치를 사용자에게 되묻지 못하게 한다 — tool_choice 강제가 없는 두 번째 패스 대비")
    void mapTailForbidsAskingUserForLocation() {
        // 첫 패스의 tool 호출은 WebSearchInterceptor 의 tool_choice 가 구조적으로 보장하지만,
        // tool 결과가 돌아온 두 번째 패스는 auto 라 여기서 되물을 여지가 남는다. 그래서 문장이 필요하다.
        String mapTail = ChatbotPrompt.modeTail(com.ssafy.ieumgil.domain.chatbot.ChatbotMode.MAP);

        // 문구 변경에 덜 취약하도록 규칙을 식별하는 최소 조각만 pin 한다.
        assertThat(mapTail).contains("지도 범위는 서버가")
                .contains("되묻지 마세요");
    }

    @Test
    @DisplayName("MAP_TAIL 은 tool 이 주지 않은 장소를 이름조차 말하지 못하게 한다 — 담을 수 없는 추천 방지")
    void mapTailForbidsNamingPlacesOutsideToolResults() {
        // 카드는 뷰포트 tool 결과로만 만들어진다. 웹에서 읽은 이름을 말하면 담기 버튼 없는 추천이 된다.
        String mapTail = ChatbotPrompt.modeTail(com.ssafy.ieumgil.domain.chatbot.ChatbotMode.MAP);

        // 웹 검색 예외 절의 결론("마찬가지입니다")이 줄바꿈 뒤에 있다 — 앞부분만 pin 하면
        // "웹 검색에 나온 곳이어도 예외입니다"로 뒤집혀도 통과한다.
        assertThat(mapTail).contains("이름조차 말하지 마세요")
                .contains("웹 검색에 나온 곳이어도")
                .contains("마찬가지입니다");
    }

    @Test
    @DisplayName("MAP_TAIL 은 웹 검색을 수식어 판정에 쓰되 출처를 밝히게 한다")
    void mapTailUsesWebSearchToHonourQualifiers() {
        String mapTail = ChatbotPrompt.modeTail(com.ssafy.ieumgil.domain.chatbot.ChatbotMode.MAP);

        // 카카오 키워드 검색이 떨어뜨리는 수식어를 웹 검색이 메우는 것이 이 모드의 새 능력이다.
        assertThat(mapTail).contains("조용한")
                .contains("웹에서 알게 된 것은 웹에서 확인한 내용임을 밝히세요");
    }

    @Test
    @DisplayName("MAP_TAIL 은 조건이 있으면 답변 전에 검색하게 하고 되묻기를 막는다")
    void mapTailMakesWebVerificationAnObligationNotAnOffer() {
        String mapTail = ChatbotPrompt.modeTail(com.ssafy.ieumgil.domain.chatbot.ChatbotMode.MAP);

        // 허용문("~해도 된다")일 때는 로컬 tool이 답을 채운 뒤 발동하지 않았다. 프로브에서 사용자가
        // 후기를 대놓고 요구했는데도 모델이 "확인해 드릴 수 있습니다, 어느 곳을 볼까요?"로 되물었다.
        assertThat(mapTail).contains("답변을 쓰기 전에")
                .contains("되묻지 말고 그 자리에서 검색");
    }

    @Test
    @DisplayName("MAP_TAIL 은 지역을 언제나 현재 지도 범위로 못박는다")
    void mapTailBindsRegionToCurrentViewportNotConversationHistory() {
        String mapTail = ChatbotPrompt.modeTail(com.ssafy.ieumgil.domain.chatbot.ChatbotMode.MAP);

        // "직전 검색어로 다시 호출" 문장이 지역까지 이어받게 읽혀, 앞선 GENERAL 턴의 "국제시장"이
        // 해운대 뷰포트를 밀어낸 사례가 있었다.
        assertThat(mapTail).contains("지역은 언제나 지금 보고 있는 지도 범위")
                .contains("앞선 턴에 나온 지역명으로 바꿔 읽지 마세요");
    }

    @Test
    @DisplayName("MAP_TAIL 은 지역 이름의 출처로 서버가 주입한 [Map view] 를 가리킨다")
    void mapTailPointsAtInjectedRegionName() {
        String mapTail = ChatbotPrompt.modeTail(com.ssafy.ieumgil.domain.chatbot.ChatbotMode.MAP);

        // 규칙만 있을 때는 행동만 고쳐지고 문장은 안 고쳐졌다 — 뷰포트를 제대로 검색해 해운대 장소만
        // 나열하면서도 첫 줄은 "국제시장 근처에서…"였다. 부를 이름을 안 줬으니 이력에서 끌어온 것이다.
        assertThat(mapTail).contains("[Map view]")
                // 역지오코딩이 실패하면 블록이 통째로 빠진다 — 그때 지어내지 말라는 절이 같이 있어야 한다
                .contains("없으면 지역명을 아예 말하지 마세요");
    }

    @Test
    @DisplayName("MAP_TAIL 은 서버 reason 을 우선하되 그 밖의 말을 금지하지는 않는다")
    void mapTailPrefersServerReasonsWithoutBanningExtras() {
        String mapTail = ChatbotPrompt.modeTail(com.ssafy.ieumgil.domain.chatbot.ChatbotMode.MAP);

        assertThat(mapTail).contains("추천 이유로 먼저 쓰세요")
                .contains("짐작임을 드러내세요");
        // 예전의 "reason 에 있는 것만 쓰세요"는 덧붙이는 말을 통째로 막아 답을 밋밋하게 만들었다.
        assertThat(mapTail).doesNotContain("reason 에 있는 것만");
    }
}
