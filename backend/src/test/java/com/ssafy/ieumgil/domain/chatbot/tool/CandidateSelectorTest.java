package com.ssafy.ieumgil.domain.chatbot.tool;

import com.ssafy.ieumgil.domain.chatbot.dto.ChatbotResDTO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CandidateSelectorTest {

    private static ChatbotResDTO.Candidate candidate(String name) {
        return ChatbotResDTO.Candidate.builder().name(name).build();
    }

    @Test
    @DisplayName("답변에 언급된 장소만 카드로 남긴다")
    void keepsOnlyMentioned() {
        List<ChatbotResDTO.Candidate> all =
                List.of(candidate("동백섬 로스터리"), candidate("해운대 북카페"), candidate("가나카페"));

        List<ChatbotResDTO.Candidate> selected = CandidateSelector.mentionedIn(
                "조용히 있기 좋은 곳으로는 동백섬 로스터리와 해운대 북카페를 추천해요.", all);

        assertThat(selected).extracting(ChatbotResDTO.Candidate::name)
                .containsExactly("동백섬 로스터리", "해운대 북카페");
    }

    @Test
    @DisplayName("하나도 못 찾으면 전체를 그대로 준다 — 이름 표기가 흔들려도 카드가 사라지면 안 된다")
    void fallsBackToAllWhenNothingMatches() {
        List<ChatbotResDTO.Candidate> all = List.of(candidate("동백섬 로스터리"), candidate("가나카페"));

        List<ChatbotResDTO.Candidate> selected =
                CandidateSelector.mentionedIn("근처에 괜찮은 카페가 몇 곳 있어요.", all);

        assertThat(selected).hasSize(2);
    }

    @Test
    @DisplayName("띄어쓰기가 달라도 같은 이름으로 본다")
    void ignoresWhitespaceDifference() {
        List<ChatbotResDTO.Candidate> all = List.of(candidate("동백섬 로스터리"), candidate("가나카페"));

        List<ChatbotResDTO.Candidate> selected =
                CandidateSelector.mentionedIn("동백섬로스터리가 좋아요.", all);

        assertThat(selected).extracting(ChatbotResDTO.Candidate::name)
                .containsExactly("동백섬 로스터리");
    }

    @Test
    @DisplayName("답변이 비어 있으면 전체를 준다")
    void fallsBackWhenReplyIsBlank() {
        List<ChatbotResDTO.Candidate> all = List.of(candidate("가나카페"));

        assertThat(CandidateSelector.mentionedIn(null, all)).hasSize(1);
        assertThat(CandidateSelector.mentionedIn("  ", all)).hasSize(1);
    }

    @Test
    @DisplayName("축제 제목의 회차 접두어(제N회)를 떼고 불러도 언급으로 인정한다")
    void survivesOrdinalPrefixInFestivalTitle() {
        List<ChatbotResDTO.Candidate> all = List.of(candidate("제28회 부산불꽃축제"), candidate("가나카페"));

        List<ChatbotResDTO.Candidate> selected =
                CandidateSelector.mentionedIn("부산불꽃축제 보러 가는 거 추천해요.", all);

        assertThat(selected).extracting(ChatbotResDTO.Candidate::name)
                .containsExactly("제28회 부산불꽃축제");
    }

    @Test
    @DisplayName("연도 접두어·괄호 부제를 떼고 불러도 언급으로 인정하고, 언급 안 된 축제는 걸러낸다")
    void survivesYearPrefixAndParenthesizedSubtitleWhileFilteringUnmentioned() {
        List<ChatbotResDTO.Candidate> all =
                List.of(candidate("2026 해운대 빛축제(야간개장)"), candidate("제28회 부산불꽃축제"));

        List<ChatbotResDTO.Candidate> selected =
                CandidateSelector.mentionedIn("해운대빛축제 다녀오기 좋아요.", all);

        assertThat(selected).extracting(ChatbotResDTO.Candidate::name)
                .containsExactly("2026 해운대 빛축제(야간개장)");
    }

    @Test
    @DisplayName("답변의 괄호 안에 있는 이름도 언급으로 인정한다 — 괄호 제거는 후보 이름에만 건다")
    void keepsCardsMentionedInsideReplyParentheses() {
        List<ChatbotResDTO.Candidate> all = List.of(candidate("블랙업커피"), candidate("동백섬 로스터리"));

        List<ChatbotResDTO.Candidate> selected = CandidateSelector.mentionedIn(
                "블랙업커피 추천! (동백섬 로스터리도 근처예요)", all);

        assertThat(selected).extracting(ChatbotResDTO.Candidate::name)
                .containsExactlyInAnyOrder("블랙업커피", "동백섬 로스터리");
    }

    @Test
    @DisplayName("지점명을 생략해 불러도(블랙업커피) 원래 이름(블랙업커피 해운대점)의 언급으로 인정한다")
    void matchesBranchAbbreviatedName() {
        List<ChatbotResDTO.Candidate> all = List.of(candidate("블랙업커피 해운대점"));

        List<ChatbotResDTO.Candidate> selected =
                CandidateSelector.mentionedIn("블랙업커피 추천드려요.", all);

        assertThat(selected).extracting(ChatbotResDTO.Candidate::name)
                .containsExactly("블랙업커피 해운대점");
    }

    @Test
    @DisplayName("지점명 생략 매칭이 있어도 언급 안 된 다른 후보는 걸러낸다")
    void filtersUnmentionedCandidateEvenWithBranchAbbreviatedMatch() {
        List<ChatbotResDTO.Candidate> all =
                List.of(candidate("블랙업커피 해운대점"), candidate("가나카페"));

        List<ChatbotResDTO.Candidate> selected =
                CandidateSelector.mentionedIn("블랙업커피 추천드려요.", all);

        assertThat(selected).extracting(ChatbotResDTO.Candidate::name)
                .containsExactly("블랙업커피 해운대점");
    }

    @Test
    @DisplayName("앞쪽 지역어를 생략해(암소갈비집) 불러도 원래 이름(해운대 암소갈비집)의 언급으로 인정하고, 함께 언급된 다른 후보도 살린다")
    void matchesLastTokenAbbreviatedNameAlongsideExactMatch() {
        List<ChatbotResDTO.Candidate> all =
                List.of(candidate("해운대 암소갈비집"), candidate("가나카페"));

        List<ChatbotResDTO.Candidate> selected =
                CandidateSelector.mentionedIn("암소갈비집이랑 가나카페 둘 다 추천해요.", all);

        assertThat(selected).extracting(ChatbotResDTO.Candidate::name)
                .containsExactlyInAnyOrder("해운대 암소갈비집", "가나카페");
    }

    @Test
    @DisplayName("마지막 토큰이 지역어면(워킹홀리데이 해운대) 답변이 그 지역어만 말해도 언급으로 인정하지 않는다")
    void doesNotMatchTrailingRegionTokenAsMention() {
        List<ChatbotResDTO.Candidate> all =
                List.of(candidate("워킹홀리데이 해운대"), candidate("가나카페"));

        List<ChatbotResDTO.Candidate> selected = CandidateSelector.mentionedIn(
                "지금 지도 범위가 해운대 쪽으로 보이는데, 국제시장 근처에서는 가나카페가 좋아요.", all);

        assertThat(selected).extracting(ChatbotResDTO.Candidate::name)
                .containsExactly("가나카페");
    }

    @Test
    @DisplayName("마지막 토큰이 다른 토큰과 길이가 같으면 언급으로 인정한다")
    void matchesLastTokenWhenTokenLengthsTie() {
        List<ChatbotResDTO.Candidate> all = List.of(candidate("광안리 물회집"), candidate("가나카페"));

        List<ChatbotResDTO.Candidate> selected =
                CandidateSelector.mentionedIn("물회집 한번 가보세요.", all);

        assertThat(selected).extracting(ChatbotResDTO.Candidate::name)
                .containsExactly("광안리 물회집");
    }

    @Test
    @DisplayName("마지막 토큰이 2자 이하이면 무관한 문장에 등장해도 언급으로 인정하지 않는다")
    void doesNotMatchShortLastTokenAsUnrelatedMention() {
        List<ChatbotResDTO.Candidate> all = List.of(candidate("동백섬 집"), candidate("가나카페"));

        List<ChatbotResDTO.Candidate> selected = CandidateSelector.mentionedIn(
                "가나카페 추천해요! 숙소는 아늑한 집이었어요.", all);

        assertThat(selected).extracting(ChatbotResDTO.Candidate::name)
                .containsExactly("가나카페");
    }
}
