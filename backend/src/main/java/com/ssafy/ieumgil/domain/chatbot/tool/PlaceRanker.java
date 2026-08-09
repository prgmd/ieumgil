package com.ssafy.ieumgil.domain.chatbot.tool;

import com.ssafy.ieumgil.domain.block.entity.BlockCategory;
import com.ssafy.ieumgil.domain.place.dto.PlaceResDTO;
import com.ssafy.ieumgil.domain.transit.util.Haversine;

import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * 지도 기반 추천 결과를 재정렬한다.
 *
 * <p>평점·리뷰 같은 품질 점수는 만들지 않는다 — 우리 데이터에 없는 값을 지어내면
 * 요금을 임의로 채우는 것과 같은 종류의 거짓이 된다. 대신 <b>근거를 댈 수 있는 신호만</b> 쓴다.
 *
 * <p>점수는 낮을수록 위다. 기준선은 카카오가 준 순서(index)이며, 이는 카카오 내부의
 * 정확도·인기 신호가 이미 반영된 값이라 버리지 않는다. 거기에 페널티만 더한다.
 *
 * <p>정렬은 안정 정렬이므로 점수가 같으면 카카오 순서가 유지된다.
 */
public final class PlaceRanker {

    /** 전국 체인. 여행지 추천에서 가치가 낮아 뒤로 민다 */
    private static final Set<String> FRANCHISE_BRANDS = Set.of(
            "스타벅스", "투썸플레이스", "이디야", "메가MGC커피", "컴포즈커피", "빽다방",
            "파스쿠찌", "할리스", "커피빈", "탐앤탐스", "더벤티", "매머드커피",
            "맥도날드", "버거킹", "롯데리아", "KFC", "맘스터치", "서브웨이",
            "파리바게뜨", "뚜레쥬르", "배스킨라빈스", "던킨", "공차", "스무디킹");

    /** 프랜차이즈 페널티. 카카오 순위 5칸에 해당한다 */
    private static final int FRANCHISE_PENALTY = 5;

    /** 이미 계획에 있는 카테고리 1건당 페널티 */
    private static final int PLANNED_CATEGORY_PENALTY = 3;

    /**
     * 중복 카테고리 페널티 상한(계획 블록 3건치).
     *
     * <p>{@link #MAX_DISTANCE_PENALTY}와 같은 이유다 — <b>어떤 단일 신호도 카카오 순서를 통째로
     * 뒤엎으면 안 된다.</b> 상한이 없으면 이 값은 보드 크기에 비례해 무한히 커져서, 블록 10건짜리
     * 보드에서는 +30이 되어 나머지 신호를 전부 합친 것(카카오 순위 + 거리 10 + 프랜차이즈 5)보다
     * 커진다. "이미 있는 종류"는 순위를 조정할 근거이지 결과를 결정할 근거가 아니다.
     */
    private static final int MAX_PLANNED_CATEGORY_PENALTY = 9;

    /** 거리 페널티 1점에 해당하는 미터. 300m 는 도보 4분쯤이다 */
    private static final double DISTANCE_UNIT_METERS = 300.0;

    /**
     * 거리 페널티 상한.
     *
     * <p>상한이 없으면 거리가 카카오 순위를 완전히 뒤엎어 "가깝기만 한 곳"이 1위가 된다.
     * 거리는 필요조건이지 품질 신호가 아니므로 영향력을 제한한다.
     */
    private static final int MAX_DISTANCE_PENALTY = 10;

    /** 도보 속도 4km/h ≈ 분당 67m. 이유 문구의 "도보 N분"에 쓴다 */
    private static final double WALK_METERS_PER_MINUTE = 67.0;

    /** 이 거리를 넘으면 "가깝다"고 말하지 않는다 — 도보 12분쯤 */
    private static final double NEAR_ENOUGH_METERS = 800.0;

    private PlaceRanker() {
    }

    /**
     * 거리 계산 기준점.
     *
     * @param name 사람이 읽을 이름. 지도 화면 중심 폴백 앵커는 이름이 없다({@code null} 허용)
     */
    public record Anchor(double lat, double lng, String name) {
    }

    /**
     * @param boardAnchors      확정된 일정 블록의 좌표들. 비어 있으면 {@code viewportCenter}를 쓴다
     * @param viewportCenter    지도 화면 중심 — 보드가 비었을 때의 폴백 기준
     * @param plannedCategories 이미 계획에 들어간 {@link BlockCategory} 이름들(중복 허용 — 개수가 곧
     *                          페널티다). 카카오 한글 그룹명("카페")이 아니라 블록 표기("FOOD")다 —
     *                          장소 쪽을 접어서 맞춘다({@link #plannedCategoryCount})
     */
    public record RankingContext(
            List<Anchor> boardAnchors,
            Anchor viewportCenter,
            List<String> plannedCategories
    ) {
    }

    public static List<PlaceResDTO.Place> rank(List<PlaceResDTO.Place> places, RankingContext context) {
        // 점수를 미리 계산해 들고 정렬한다. 비교자 안에서 indexOf 를 부르면 O(n²)인 데다
        // 같은 값의 장소가 둘이면 둘 다 첫 번째 인덱스를 받아 순위가 뒤엉킨다.
        List<Scored> scored = new java.util.ArrayList<>(places.size());
        for (int i = 0; i < places.size(); i++) {
            scored.add(new Scored(places.get(i), score(places.get(i), i, context)));
        }
        // List.sort 는 안정 정렬이라 점수가 같으면 카카오 순서가 유지된다
        scored.sort(Comparator.comparingInt(Scored::score));
        return scored.stream().map(Scored::place).toList();
    }

    private record Scored(PlaceResDTO.Place place, int score) {
    }

    private static int score(PlaceResDTO.Place place, int originalIndex, RankingContext context) {
        int score = originalIndex;
        if (isFranchise(place)) {
            score += FRANCHISE_PENALTY;
        }
        score += (int) Math.min(
                plannedCategoryCount(place, context) * PLANNED_CATEGORY_PENALTY,
                MAX_PLANNED_CATEGORY_PENALTY);
        score += distancePenalty(place, context);
        return score;
    }

    /**
     * 확정된 일정 중 가장 가까운 것까지의 거리를 점수로 바꾼다.
     *
     * <p>여행에서는 "좋은 곳"보다 "동선에 맞는 곳"이 실제로 유용하고, 거리는 우리가
     * 계산으로 근거를 댈 수 있는 몇 안 되는 신호다. 보드가 비었으면 지도 화면 중심을 쓴다.
     */
    private static int distancePenalty(PlaceResDTO.Place place, RankingContext context) {
        NearestAnchor nearest = nearestAnchor(place, context);
        if (nearest == null) {
            return 0;
        }
        return (int) Math.min(MAX_DISTANCE_PENALTY, Math.round(nearest.meters() / DISTANCE_UNIT_METERS));
    }

    /** 가장 가까운 기준점과 그 거리(m) */
    private record NearestAnchor(Anchor anchor, double meters) {
    }

    /**
     * 가장 가까운 기준점(과 그 거리). 좌표가 없으면 null.
     *
     * <p>점수({@link #distancePenalty})와 이유 문구({@link #reasonsFor})가 <b>같은 계산 결과</b>를
     * 나눠 쓰게 한 곳에 둔다. 따로 두면 한쪽만 손댔을 때 "도보 4분"이라고 말하면서 점수는 다른
     * 거리로 매기거나, 이유 문구가 점수와 다른 앵커를 가리키는, 설명과 순위가 어긋난 상태가
     * 조용히 생긴다.
     */
    private static NearestAnchor nearestAnchor(PlaceResDTO.Place place, RankingContext context) {
        if (place.lat() == null || place.lng() == null) {
            return null;
        }
        List<Anchor> anchors = context.boardAnchors().isEmpty()
                ? List.of(context.viewportCenter())
                : context.boardAnchors();
        return anchors.stream()
                .map(a -> new NearestAnchor(a, Haversine.distanceMeters(a.lat(), a.lng(), place.lat(), place.lng())))
                .min(Comparator.comparingDouble(NearestAnchor::meters))
                .orElse(null);
    }

    /**
     * 추천 이유를 만든다. <b>계산으로 뒷받침되는 것만</b> 담는다.
     *
     * <p>모델에게 이유를 자유롭게 쓰게 두면 "리뷰가 좋아서" 같은 문장이 나온다 — 평점
     * 데이터가 우리에게 없으므로 근거 없는 주장이다. 서버가 사실을 계산해 넘기면 모델이
     * 지어낼 이유가 줄어든다(축제 기간 겹침을 계산해 넘긴 것과 같은 방어).
     *
     * <p>해당하지 않으면 <b>비운다.</b> 없는 장점을 만들지 않는다.
     */
    public static List<String> reasonsFor(PlaceResDTO.Place place, RankingContext context) {
        List<String> reasons = new java.util.ArrayList<>();

        NearestAnchor nearest = nearestAnchor(place, context);
        if (nearest != null && nearest.meters() <= NEAR_ENOUGH_METERS) {
            int walkMinutes = Math.max(1, (int) Math.round(nearest.meters() / WALK_METERS_PER_MINUTE));
            reasons.add("%s에서 도보 %d분 거리".formatted(anchorLabel(nearest.anchor(), context), walkMinutes));
        }
        if (!isFranchise(place)) {
            reasons.add("체인점이 아닌 곳");
        }
        // 계획이 비어 있으면 모든 장소가 "없는 종류"라 정보가 아니다 — 그때는 붙이지 않는다
        if (plannedCategoryCount(place, context) == 0 && !context.plannedCategories().isEmpty()) {
            reasons.add("지금 일정에 없는 종류");
        }
        return List.copyOf(reasons);
    }

    /**
     * 이유 문구에 쓸 앵커 이름. 이름이 있으면 실제로 가장 가까웠던 그곳을 그대로 부르고,
     * 없으면(지도 중심 폴백, 또는 이름 없는 블록) 예전처럼 뭉뚱그린 표현을 쓴다.
     */
    private static String anchorLabel(Anchor anchor, RankingContext context) {
        if (anchor.name() != null && !anchor.name().isBlank()) {
            return anchor.name();
        }
        return context.boardAnchors().isEmpty() ? "지도 중심" : "계획한 일정";
    }

    private static boolean isFranchise(PlaceResDTO.Place place) {
        String path = place.categoryPath() == null ? "" : place.categoryPath();
        String name = place.name() == null ? "" : place.name();
        return FRANCHISE_BRANDS.stream()
                .anyMatch(brand -> path.contains(brand) || name.contains(brand));
    }

    /**
     * 이미 계획에 있는 같은 종류의 개수를 센다.
     *
     * <p>양쪽 표기를 <b>블록 카테고리</b>로 맞춘다. 보드가 아는 것은 {@link BlockCategory}뿐이고,
     * 장소가 아는 것은 카카오 한글 그룹명("카페")뿐이라 그대로 비교하면 한 번도 안 걸린다.
     * 카카오→블록은 {@code CandidateCollector.toBlockCategory}가 이미 하는 전사 함수지만
     * 그 역은 함수가 아니다(SPOT 이 "그 외" 전부를 받는다). 그래서 장소 쪽을 접는다 —
     * 접은 값은 곧 이 카드가 만들어낼 블록의 카테고리라, 비교의 의미도 정확히 그것이다.
     */
    private static long plannedCategoryCount(PlaceResDTO.Place place, RankingContext context) {
        if (place.category() == null) {
            return 0;
        }
        String blockCategory = CandidateCollector.toBlockCategory(place.category()).name();
        return context.plannedCategories().stream()
                .filter(blockCategory::equals)
                .count();
    }
}
