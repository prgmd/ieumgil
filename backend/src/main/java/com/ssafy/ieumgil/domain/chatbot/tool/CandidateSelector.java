package com.ssafy.ieumgil.domain.chatbot.tool;

import com.ssafy.ieumgil.domain.chatbot.dto.ChatbotResDTO;

import java.util.List;

/**
 * 모델이 답변에서 실제로 언급한 장소만 카드로 남긴다.
 *
 * <p>카드는 tool 실행의 side-effect라 검색 결과가 전부 실린다. 그러면 모델이 "이 중 A가
 * 좋겠네요"라고 답해도 카드는 전부 뜨어 <b>텍스트와 카드가 어긋난다</b>.
 *
 * <p>모델에게 선택 tool을 새로 주는 대신 답변 텍스트로 거른다 — tool을 늘리면 haiku의
 * 선택 정확도가 떨어지고, 모델이 그 tool을 안 부르면 카드가 0개가 되어 지금보다 나빠진다.
 * 여기서는 최악의 경우가 "지금과 같음"(전체 반환)이다.
 */
public final class CandidateSelector {

    private CandidateSelector() {
    }

    public static List<ChatbotResDTO.Candidate> mentionedIn(
            String reply, List<ChatbotResDTO.Candidate> all) {
        if (reply == null || reply.isBlank()) {
            return all;
        }
        String normalizedReply = normalizeReply(reply);
        List<ChatbotResDTO.Candidate> mentioned = all.stream()
                .filter(c -> c.name() != null && isMentioned(normalizedReply, c.name()))
                .toList();
        // 이름 표기가 흔들리면(지점명 생략 등) 전부 걸러질 수 있다. 그때는 거르지 않는다.
        return mentioned.isEmpty() ? all : mentioned;
    }

    private static boolean isMentioned(String normalizedReply, String name) {
        if (normalizedReply.contains(normalizeName(name))) {
            return true;
        }
        // 전체 명칭이 안 맞으면 지점명을 뗀 축약형("블랙업커피 해운대점" → "블랙업커피")도 본다.
        String trimmed = name.strip();
        String branchStripped = stripBranchSuffix(trimmed);
        if (!branchStripped.equals(trimmed) && normalizedReply.contains(normalizeName(branchStripped))) {
            return true;
        }
        // 지점명이 아니라 앞쪽 지역어를 생략한 경우("해운대 암소갈비집" → "암소갈비집")도 본다.
        // 마지막 토큰만 보되, 아래 두 조건을 모두 만족할 때만 적용한다.
        //   ① 3자 이상 — 2자 이하는 무관한 문장에도 흔히 끼어들어 오탐이 난다.
        //   ② 다른 모든 토큰만큼 길다(동률 허용) — 한국어 상호에서 변별력 있는 부분은 대개
        //      더 긴 토큰이고, 뒤에 붙는 지역어는 대개 더 짧다. 이 비교가 없으면 지역어가
        //      뒤에 오는 이름에서 오탐이 난다: "워킹홀리데이 해운대"(6자/3자)는 마지막 토큰이
        //      "해운대"라, 모델이 아무 장소도 추천하지 않고 "지금 지도 범위가 해운대 쪽으로
        //      보이는데…"라고만 말해도 카드가 떠 버린다(실제 관측된 오작동). 반대로
        //      "해운대 암소갈비집"(3자/5자)은 마지막 토큰이 최장이라 그대로 매칭된다.
        String[] tokens = trimmed.split("\\s+");
        String lastToken = tokens[tokens.length - 1];
        return lastToken.length() >= 3
                && isLongestToken(tokens, lastToken)
                && normalizedReply.contains(normalizeName(lastToken));
    }

    private static boolean isLongestToken(String[] tokens, String lastToken) {
        for (String token : tokens) {
            if (token.length() > lastToken.length()) {
                return false;
            }
        }
        return true;
    }

    /**
     * 답변(haystack) 쪽은 공백만 지운다. 답변 본문에서 내용을 지우면(예: 괄호 제거) 매칭을
     * 잃을 수만 있다 — 모델이 "블랙업커피 추천! (동백섬 로스터리도 근처예요)"처럼 괄호
     * 안에서 장소를 언급하면, 답변에 괄호 제거를 걸 경우 그 장소의 카드가 사라진다.
     */
    private static String normalizeReply(String reply) {
        return reply.replaceAll("\\s+", "");
    }

    /**
     * 후보 이름 쪽은 공백 제거에 더해 축제 제목에 흔한 접두어·부제를 뗀다. TourAPI 제목은
     * "제28회 부산불꽃축제", "2026 해운대 빛축제(야간개장)"처럼 회차·연도·괄호 부제를 달고
     * 오는데, 모델이 답변에서 이 부분을 빼고 부르면(예: "부산불꽃축제") 공백 제거만으로는
     * 매칭이 안 돼 카드가 사라진다. 이 정규화는 이름에만 건다 — 답변에 걸면 위 이유로 안 된다.
     */
    private static String normalizeName(String name) {
        String normalized = name.replaceAll("\\s+", "");
        normalized = normalized.replaceAll("^제\\d+회", "");
        normalized = normalized.replaceAll("^\\d{4}", "");
        normalized = normalized.replaceAll("\\([^)]*\\)", "");
        return normalized;
    }

    /**
     * 지점명(마지막 토큰이 "~점"으로 끝나는 부분)을 뗀 축약형. 모델이 "블랙업커피 해운대점"을
     * "블랙업커피"로 줄여 부르는 경우를 잡는다. {@code MapBlockabilityLiveTest.stripBranchSuffix}와
     * 같은 규칙이지만, 운영 코드가 테스트에 의존하면 안 되므로 여기서 다시 구현한다.
     */
    private static String stripBranchSuffix(String name) {
        int lastSpace = name.lastIndexOf(' ');
        if (lastSpace > 0 && name.endsWith("점")) {
            return name.substring(0, lastSpace);
        }
        return name;
    }
}
