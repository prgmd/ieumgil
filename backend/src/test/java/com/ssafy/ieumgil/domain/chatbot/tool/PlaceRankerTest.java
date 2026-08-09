package com.ssafy.ieumgil.domain.chatbot.tool;

import com.ssafy.ieumgil.domain.place.dto.PlaceResDTO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PlaceRankerTest {

    private static final PlaceRanker.Anchor CENTER = new PlaceRanker.Anchor(35.160, 129.160, null);

    private static PlaceResDTO.Place place(String name, String categoryPath, String category) {
        return PlaceResDTO.Place.builder()
                .placeId(name).name(name).address("부산 해운대구")
                .lat(35.160).lng(129.160)
                .category(category).categoryCode("CE7").categoryPath(categoryPath)
                .build();
    }

    private static PlaceRanker.RankingContext context(List<String> plannedCategories) {
        return new PlaceRanker.RankingContext(List.of(), CENTER, plannedCategories);
    }

    @Test
    @DisplayName("신호가 없으면 카카오가 준 순서를 그대로 유지한다")
    void keepsOriginalOrderWhenNoSignal() {
        List<PlaceResDTO.Place> places = List.of(
                place("가카페", "음식점 > 카페", "카페"),
                place("나카페", "음식점 > 카페", "카페"),
                place("다카페", "음식점 > 카페", "카페"));

        List<PlaceResDTO.Place> ranked = PlaceRanker.rank(places, context(List.of()));

        assertThat(ranked).extracting(PlaceResDTO.Place::name)
                .containsExactly("가카페", "나카페", "다카페");
    }

    @Test
    @DisplayName("프랜차이즈는 뒤로 밀린다 — 여행지에서 전국 체인은 추천 가치가 낮다")
    void franchiseIsPushedDown() {
        List<PlaceResDTO.Place> places = List.of(
                place("스타벅스 해운대점", "음식점 > 카페 > 커피전문점 > 스타벅스", "카페"),
                place("동백섬 로스터리", "음식점 > 카페", "카페"));

        List<PlaceResDTO.Place> ranked = PlaceRanker.rank(places, context(List.of()));

        assertThat(ranked).extracting(PlaceResDTO.Place::name)
                .containsExactly("동백섬 로스터리", "스타벅스 해운대점");
    }

    @Test
    @DisplayName("상호명만으로도 프랜차이즈를 잡는다 — 카테고리에 브랜드가 없을 수 있다")
    void franchiseDetectedByName() {
        List<PlaceResDTO.Place> places = List.of(
                place("메가MGC커피 해운대점", "음식점 > 카페", "카페"),
                place("동백섬 로스터리", "음식점 > 카페", "카페"));

        List<PlaceResDTO.Place> ranked = PlaceRanker.rank(places, context(List.of()));

        assertThat(ranked).extracting(PlaceResDTO.Place::name)
                .containsExactly("동백섬 로스터리", "메가MGC커피 해운대점");
    }

    @Test
    @DisplayName("이미 계획에 있는 카테고리는 뒤로 밀린다")
    void plannedCategoryIsPushedDown() {
        List<PlaceResDTO.Place> places = List.of(
                place("가카페", "음식점 > 카페", "카페"),
                place("나전망대", "여행 > 관광,명소 > 전망대", "관광명소"));

        List<PlaceResDTO.Place> ranked =
                PlaceRanker.rank(places, context(List.of("FOOD", "FOOD")));

        assertThat(ranked).extracting(PlaceResDTO.Place::name)
                .containsExactly("나전망대", "가카페");
    }

    @Test
    @DisplayName("계획 비교는 블록 카테고리 표기로 한다 — 카카오 한글 그룹명끼리 비교하면 영원히 안 걸린다")
    void plannedCategoryIsComparedInBlockVocabulary() {
        // "카페"와 "음식점"은 둘 다 FOOD 로 접힌다 — 카드가 만들어질 블록의 종류가 곧 비교 대상이다
        List<PlaceResDTO.Place> places = List.of(
                place("가횟집", "음식점 > 한식 > 횟집", "음식점"),
                place("나전망대", "여행 > 관광,명소 > 전망대", "관광명소"));

        List<PlaceResDTO.Place> ranked =
                PlaceRanker.rank(places, context(List.of("FOOD", "FOOD")));

        assertThat(ranked).extracting(PlaceResDTO.Place::name)
                .containsExactly("나전망대", "가횟집");
    }

    @Test
    @DisplayName("중복 카테고리 페널티에도 상한이 있어 다른 신호를 통째로 덮지 않는다")
    void plannedCategoryPenaltyIsCapped() {
        // 보드에 카페·음식점 블록이 10개 있는 상황. 상한이 없으면 +30이라
        // 나머지 신호(카카오 순위 + 거리 상한 10 + 프랜차이즈 5)를 전부 합쳐도 못 이긴다.
        List<String> planned = java.util.Collections.nCopies(10, "FOOD");

        PlaceResDTO.Place plannedKind = PlaceResDTO.Place.builder()
                .placeId("a").name("계획된종류카페").address("부산")
                .lat(35.160).lng(129.160)                   // 기준점과 같은 자리 — 거리 0
                .category("카페").categoryPath("음식점 > 카페").build();
        PlaceResDTO.Place farFranchise = PlaceResDTO.Place.builder()
                .placeId("b").name("스타벅스 서울점").address("서울")
                .lat(37.5665).lng(126.9780)                 // 수백 km — 거리 상한 10
                .category("관광명소").categoryPath("여행 > 관광,명소").build();

        // 계획된종류카페 = 0(카카오 1위) + min(30, 9) = 9
        // 스타벅스      = 1(카카오 2위) + 10(거리 상한) + 5(프랜차이즈) = 16
        List<PlaceResDTO.Place> ranked = PlaceRanker.rank(
                List.of(plannedKind, farFranchise),
                new PlaceRanker.RankingContext(List.of(CENTER), CENTER, planned));

        assertThat(ranked).extracting(PlaceResDTO.Place::name)
                .containsExactly("계획된종류카페", "스타벅스 서울점");
    }

    @Test
    @DisplayName("확정 일정에서 먼 곳은 뒤로 밀린다")
    void farFromBoardIsPushedDown() {
        // 보드 앵커: 해운대 해수욕장
        PlaceRanker.Anchor board = new PlaceRanker.Anchor(35.1587, 129.1604, null);

        PlaceResDTO.Place far = PlaceResDTO.Place.builder()
                .placeId("far").name("먼카페").address("부산")
                .lat(35.2000).lng(129.2200)   // 약 6km
                .category("카페").categoryPath("음식점 > 카페").build();
        PlaceResDTO.Place near = PlaceResDTO.Place.builder()
                .placeId("near").name("가까운카페").address("부산")
                .lat(35.1590).lng(129.1610)   // 약 60m
                .category("카페").categoryPath("음식점 > 카페").build();

        PlaceRanker.RankingContext context =
                new PlaceRanker.RankingContext(List.of(board), CENTER, List.of());

        List<PlaceResDTO.Place> ranked = PlaceRanker.rank(List.of(far, near), context);

        assertThat(ranked).extracting(PlaceResDTO.Place::name)
                .containsExactly("가까운카페", "먼카페");
    }

    @Test
    @DisplayName("보드가 비면 지도 화면 중심을 기준으로 삼는다")
    void fallsBackToViewportCenterWhenBoardIsEmpty() {
        PlaceResDTO.Place far = PlaceResDTO.Place.builder()
                .placeId("far").name("먼카페").address("부산")
                .lat(35.2000).lng(129.2200)
                .category("카페").categoryPath("음식점 > 카페").build();
        PlaceResDTO.Place near = PlaceResDTO.Place.builder()
                .placeId("near").name("가까운카페").address("부산")
                .lat(35.1601).lng(129.1601)
                .category("카페").categoryPath("음식점 > 카페").build();

        List<PlaceResDTO.Place> ranked = PlaceRanker.rank(List.of(far, near), context(List.of()));

        assertThat(ranked).extracting(PlaceResDTO.Place::name)
                .containsExactly("가까운카페", "먼카페");
    }

    @Test
    @DisplayName("거리 페널티에 상한이 있어 카카오 순위를 완전히 뒤엎지 않는다")
    void distancePenaltyIsCapped() {
        PlaceRanker.Anchor board = new PlaceRanker.Anchor(35.1587, 129.1604, null);
        // 첫 번째는 아주 멀지만(상한 10) 카카오 1위, 두 번째는 가깝지만 카카오 12위
        PlaceResDTO.Place first = PlaceResDTO.Place.builder()
                .placeId("a").name("먼1위").address("부산")
                .lat(37.5665).lng(126.9780)   // 서울 — 수백 km
                .category("카페").categoryPath("음식점 > 카페").build();
        PlaceResDTO.Place near = PlaceResDTO.Place.builder()
                .placeId("b").name("가까운꼴찌").address("부산")
                .lat(35.1590).lng(129.1610)
                .category("카페").categoryPath("음식점 > 카페").build();

        List<PlaceResDTO.Place> places = new java.util.ArrayList<>();
        places.add(first);
        for (int i = 0; i < 11; i++) {
            places.add(PlaceResDTO.Place.builder()
                    .placeId("p" + i).name("채움" + i).address("부산")
                    .lat(35.1587).lng(129.1604)
                    .category("카페").categoryPath("음식점 > 카페").build());
        }
        places.add(near);

        PlaceRanker.RankingContext context =
                new PlaceRanker.RankingContext(List.of(board), CENTER, List.of());

        List<PlaceResDTO.Place> ranked = PlaceRanker.rank(places, context);

        // 먼1위 = 0(카카오 1위) + 10(거리 상한) = 10, 가까운꼴찌 = 12(카카오 13위) + 0 = 12.
        // 상한이 없었다면 먼1위 점수가 수백이라 꼴찌로 밀렸을 것이다.
        assertThat(ranked).extracting(PlaceResDTO.Place::name)
                .containsSubsequence("먼1위", "가까운꼴찌");
    }

    @Test
    @DisplayName("categoryPath 가 null 이어도 터지지 않는다")
    void nullCategoryPathIsSafe() {
        List<PlaceResDTO.Place> places = List.of(
                place("이름없음", null, "카페"));

        List<PlaceResDTO.Place> ranked = PlaceRanker.rank(places, context(List.of()));

        assertThat(ranked).hasSize(1);
    }

    @Test
    @DisplayName("계획한 일정에서 가까우면 도보 시간을 이유로 준다")
    void reasonMentionsWalkingTimeWhenNear() {
        PlaceRanker.Anchor board = new PlaceRanker.Anchor(35.1587, 129.1604, null);
        PlaceResDTO.Place near = PlaceResDTO.Place.builder()
                .placeId("near").name("가까운카페").address("부산")
                .lat(35.1590).lng(129.1610)   // 약 60m
                .category("카페").categoryPath("음식점 > 카페").build();

        List<String> reasons = PlaceRanker.reasonsFor(near,
                new PlaceRanker.RankingContext(List.of(board), CENTER, List.of()));

        assertThat(reasons).anySatisfy(r -> assertThat(r).contains("도보"));
    }

    @Test
    @DisplayName("멀면 거리 이유를 붙이지 않는다 — 없는 장점을 만들지 않는다")
    void noDistanceReasonWhenFar() {
        PlaceRanker.Anchor board = new PlaceRanker.Anchor(35.1587, 129.1604, null);
        PlaceResDTO.Place far = PlaceResDTO.Place.builder()
                .placeId("far").name("먼카페").address("부산")
                .lat(35.3000).lng(129.4000)
                .category("카페").categoryPath("음식점 > 카페").build();

        List<String> reasons = PlaceRanker.reasonsFor(far,
                new PlaceRanker.RankingContext(List.of(board), CENTER, List.of()));

        assertThat(reasons).noneSatisfy(r -> assertThat(r).contains("도보"));
    }

    @Test
    @DisplayName("앵커에 이름이 있으면 그 이름을 이유에 그대로 쓴다")
    void reasonNamesTheAnchorWhenNamed() {
        PlaceRanker.Anchor namedBoard =
                new PlaceRanker.Anchor(35.1587, 129.1604, "해운대암소갈비집");
        PlaceResDTO.Place near = PlaceResDTO.Place.builder()
                .placeId("near").name("가까운카페").address("부산")
                .lat(35.1590).lng(129.1610)   // 약 60m
                .category("카페").categoryPath("음식점 > 카페").build();

        List<String> reasons = PlaceRanker.reasonsFor(near,
                new PlaceRanker.RankingContext(List.of(namedBoard), CENTER, List.of()));

        assertThat(reasons).anySatisfy(r -> assertThat(r).contains("해운대암소갈비집"));
    }

    @Test
    @DisplayName("이름 없는 앵커(지도 중심 폴백)는 '지도 중심'이라고 부른다")
    void reasonUsesMapCenterWordingWhenAnchorIsUnnamed() {
        PlaceResDTO.Place near = PlaceResDTO.Place.builder()
                .placeId("near").name("가까운카페").address("부산")
                .lat(35.160).lng(129.160)   // CENTER 와 동일 — 거리 0
                .category("카페").categoryPath("음식점 > 카페").build();

        List<String> reasons = PlaceRanker.reasonsFor(near, context(List.of()));

        assertThat(reasons).anySatisfy(r -> assertThat(r).contains("지도 중심"));
    }

    @Test
    @DisplayName("앵커가 여럿이면 실제로 가장 가까운 앵커의 이름을 쓴다 — 엉뚱한 앵커를 부르지 않는다")
    void reasonNamesTheNearestAnchorAmongSeveral() {
        // near: 해수욕장 바로 옆(약 60m) — 목록 한가운데에 둔다. 가까운 앵커가 첫/마지막
        // 인덱스에 있으면 "무조건 첫 번째(혹은 마지막) 앵커를 고른다"는 결함도 우연히
        // 정답을 맞혀 테스트를 통과시킨다 — 그래서 양쪽 끝을 모두 먼 앵커로 채운다.
        // far1·far2 는 서로 거리도 다르게 둬서 "최대 거리 앵커를 고른다"는 결함도 잡는다.
        PlaceRanker.Anchor far1 = new PlaceRanker.Anchor(35.2500, 129.3000, "먼앵커1");
        PlaceRanker.Anchor near = new PlaceRanker.Anchor(35.1587, 129.1604, "가까운앵커");
        PlaceRanker.Anchor far2 = new PlaceRanker.Anchor(35.0500, 129.0000, "먼앵커2");
        PlaceResDTO.Place place = PlaceResDTO.Place.builder()
                .placeId("p").name("카페").address("부산")
                .lat(35.1590).lng(129.1610)
                .category("카페").categoryPath("음식점 > 카페").build();

        List<String> reasons = PlaceRanker.reasonsFor(place,
                new PlaceRanker.RankingContext(List.of(far1, near, far2), CENTER, List.of()));

        assertThat(reasons).anySatisfy(r -> assertThat(r).contains("가까운앵커"));
        assertThat(reasons).noneSatisfy(r -> assertThat(r).contains("먼앵커"));
    }

    @Test
    @DisplayName("체인이 아니면 이유로 준다")
    void reasonMentionsNotFranchise() {
        PlaceResDTO.Place local = place("동백섬 로스터리", "음식점 > 카페", "카페");

        List<String> reasons = PlaceRanker.reasonsFor(local, context(List.of()));

        assertThat(reasons).contains("체인점이 아닌 곳");
    }

    @Test
    @DisplayName("프랜차이즈에는 '체인점이 아닌 곳'을 붙이지 않는다")
    void noFranchiseReasonForChain() {
        PlaceResDTO.Place chain = place("스타벅스 해운대점", "음식점 > 카페 > 커피전문점 > 스타벅스", "카페");

        List<String> reasons = PlaceRanker.reasonsFor(chain, context(List.of()));

        assertThat(reasons).doesNotContain("체인점이 아닌 곳");
    }

    @Test
    @DisplayName("일정에 없는 종류면 이유로 준다")
    void reasonMentionsNewCategory() {
        // 계획에 숙박(STAY)만 있고 후보는 음식점(FOOD) — 실제로 없는 종류다.
        // plannedCategories 는 블록 카테고리 표기이므로 카카오 한글 그룹명을 넣으면 안 된다.
        PlaceResDTO.Place restaurant = place("나횟집", "음식점 > 한식 > 횟집", "음식점");

        List<String> reasons = PlaceRanker.reasonsFor(restaurant, context(List.of("STAY")));

        assertThat(reasons).contains("지금 일정에 없는 종류");
    }

    @Test
    @DisplayName("이미 일정에 있는 종류면 '없는 종류'라고 하지 않는다 — 카카오 이름을 접어서 비교해야 잡힌다")
    void noNewCategoryReasonWhenAlreadyPlanned() {
        // "카페"는 FOOD 로 접히므로 계획의 FOOD 와 같은 종류다. 접지 않고 원문끼리
        // 비교하면("카페" vs "FOOD") 영원히 안 걸려 이 이유가 잘못 붙는다.
        PlaceResDTO.Place cafe = place("동백섬 로스터리", "음식점 > 카페", "카페");

        List<String> reasons = PlaceRanker.reasonsFor(cafe, context(List.of("FOOD")));

        assertThat(reasons).doesNotContain("지금 일정에 없는 종류");
    }

    @Test
    @DisplayName("계획이 비었으면 '없는 종류'를 붙이지 않는다 — 전부 해당돼 정보가 아니다")
    void noNewCategoryReasonWhenNothingIsPlanned() {
        PlaceResDTO.Place cafe = place("동백섬 로스터리", "음식점 > 카페", "카페");

        List<String> reasons = PlaceRanker.reasonsFor(cafe, context(List.of()));

        assertThat(reasons).doesNotContain("지금 일정에 없는 종류");
    }
}
