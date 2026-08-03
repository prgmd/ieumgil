package com.ssafy.ieumgil.domain.chatbot.tool;

import com.ssafy.ieumgil.domain.block.entity.BlockCategory;
import com.ssafy.ieumgil.domain.block.entity.BlockSource;
import com.ssafy.ieumgil.domain.chatbot.dto.ChatbotResDTO;
import com.ssafy.ieumgil.domain.festival.entity.Festival;
import com.ssafy.ieumgil.domain.place.dto.PlaceResDTO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * tool 실행 결과를 응답의 candidates[]로 모으는 수집기 (BOT-04).
 *
 * <p>LLM에 넘기는 요약(PlaceSearchSummary·FestivalSummary)에는 좌표가 없으므로
 * 수집기는 원본 도메인 객체를 받아 블록 생성에 필요한 필드를 채운다.
 */
class CandidateCollectorTest {

    private static PlaceResDTO.Place place(String id, String name, String categoryGroupName) {
        return PlaceResDTO.Place.builder()
                .placeId(id)
                .name(name)
                .address("제주 서귀포시")
                .lat(33.45)
                .lng(126.93)
                .category(categoryGroupName)
                .build();
    }

    private static Festival festival(String contentId, Double lat, Double lng) {
        return Festival.builder()
                .contentId(contentId)
                .title("제주 불빛축제")
                .category("EV01")
                .addr("제주 제주시")
                .lat(lat)
                .lng(lng)
                .eventStartDate(LocalDate.of(2026, 8, 11))
                .eventEndDate(LocalDate.of(2026, 8, 12))
                .build();
    }

    @Test
    @DisplayName("아무것도 안 모으면 빈 배열이다 — null이 아니어야 프론트가 방어 코드를 안 쓴다")
    void emptyCollectorYieldsEmptyList() {
        assertThat(new CandidateCollector().candidates()).isEmpty();
    }

    @Test
    @DisplayName("장소는 블록 생성에 필요한 필드를 모두 채우고 source=KAKAO다")
    void placeBecomesKakaoCandidate() {
        CandidateCollector collector = new CandidateCollector();

        collector.addPlace(place("1", "스타벅스 성산일출봉점", "카페"));

        ChatbotResDTO.Candidate c = collector.candidates().get(0);
        assertThat(c.name()).isEqualTo("스타벅스 성산일출봉점");
        assertThat(c.placeId()).isEqualTo("1");
        assertThat(c.lat()).isEqualTo(33.45);
        assertThat(c.lng()).isEqualTo(126.93);
        assertThat(c.address()).isEqualTo("제주 서귀포시");
        assertThat(c.source()).isEqualTo(BlockSource.KAKAO);
        assertThat(c.subCategory()).isEqualTo("카페");
        assertThat(c.eventStartDate()).isNull();
        assertThat(c.eventEndDate()).isNull();
    }

    @Test
    @DisplayName("카카오 카테고리를 블록 카테고리로 접는다 — 카페·음식점은 FOOD, 숙박은 STAY")
    void mapsKakaoCategoryToBlockCategory() {
        CandidateCollector collector = new CandidateCollector();

        collector.addPlace(place("1", "카페", "카페"));
        collector.addPlace(place("2", "식당", "음식점"));
        collector.addPlace(place("3", "호텔", "숙박"));
        collector.addPlace(place("4", "명소", "관광명소"));

        assertThat(collector.candidates())
                .extracting(ChatbotResDTO.Candidate::category)
                .containsExactly(
                        BlockCategory.FOOD, BlockCategory.FOOD,
                        BlockCategory.STAY, BlockCategory.SPOT
                );
    }

    @Test
    @DisplayName("카테고리가 빈 장소도 있다(실제 카카오 응답 확인) — SPOT으로 떨어뜨린다")
    void blankCategoryFallsBackToSpot() {
        CandidateCollector collector = new CandidateCollector();

        collector.addPlace(place("1", "무분류 장소", ""));

        assertThat(collector.candidates().get(0).category()).isEqualTo(BlockCategory.SPOT);
    }

    @Test
    @DisplayName("축제는 SPOT + source=BOT이고 기간을 함께 싣는다")
    void festivalBecomesBotCandidateWithPeriod() {
        CandidateCollector collector = new CandidateCollector();

        collector.addFestival(festival("999", 33.5, 126.5));

        ChatbotResDTO.Candidate c = collector.candidates().get(0);
        assertThat(c.name()).isEqualTo("제주 불빛축제");
        assertThat(c.placeId()).isEqualTo("999");
        assertThat(c.category()).isEqualTo(BlockCategory.SPOT);
        assertThat(c.source()).isEqualTo(BlockSource.BOT);
        assertThat(c.subCategory()).isEqualTo("축제");
        assertThat(c.eventStartDate()).isEqualTo("2026-08-11");
        assertThat(c.eventEndDate()).isEqualTo("2026-08-12");
    }

    @Test
    @DisplayName("좌표 없는 축제는 후보에서 제외한다 — 장소성 블록은 좌표가 필수라 프론트가 BLOCK400을 맞는다")
    void festivalWithoutCoordinatesIsExcluded() {
        CandidateCollector collector = new CandidateCollector();

        collector.addFestival(festival("999", null, null));

        assertThat(collector.candidates()).isEmpty();
    }

    @Test
    @DisplayName("같은 장소가 여러 번 검색돼도 하나만 남고, 처음 등장한 순서를 지킨다")
    void deduplicatesByIdKeepingFirstOrder() {
        CandidateCollector collector = new CandidateCollector();

        collector.addPlace(place("1", "먼저", "카페"));
        collector.addPlace(place("2", "나중", "음식점"));
        collector.addPlace(place("1", "중복", "카페"));

        assertThat(collector.candidates())
                .extracting(ChatbotResDTO.Candidate::name)
                .containsExactly("먼저", "나중");
    }

    @Test
    @DisplayName("장소와 축제가 같은 id를 가져도 출처가 달라 서로 지우지 않는다")
    void placeAndFestivalWithSameIdCoexist() {
        CandidateCollector collector = new CandidateCollector();

        collector.addPlace(place("777", "장소", "카페"));
        collector.addFestival(festival("777", 33.5, 126.5));

        assertThat(collector.candidates()).hasSize(2);
    }

    @Test
    @DisplayName("반환된 목록은 수정할 수 없다 — 수집 후 누가 끼워넣는 일을 막는다")
    void returnedListIsUnmodifiable() {
        CandidateCollector collector = new CandidateCollector();
        collector.addPlace(place("1", "장소", "카페"));

        List<ChatbotResDTO.Candidate> candidates = collector.candidates();

        assertThat(candidates).isUnmodifiable();
    }

    @Test
    @DisplayName("축제는 개최 기간을 detail 문구로 만들어 넘긴다 — 블록에는 기간 컬럼이 없어 자유텍스트로만 남길 수 있다")
    void festivalCarriesPreparedDetailText() {
        CandidateCollector collector = new CandidateCollector();

        collector.addFestival(festival("999", 33.5, 126.5));

        assertThat(collector.candidates().get(0).detail())
                .isEqualTo("개최 기간: 2026-08-11 ~ 2026-08-12");
    }

    @Test
    @DisplayName("장소는 detail이 비어 있다 — 카카오가 주는 정보로는 채울 내용이 없다")
    void placeHasNoDetail() {
        CandidateCollector collector = new CandidateCollector();

        collector.addPlace(place("1", "카페", "카페"));

        assertThat(collector.candidates().get(0).detail()).isNull();
    }
}
