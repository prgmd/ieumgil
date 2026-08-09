package com.ssafy.ieumgil.domain.chatbot.tool;

import com.ssafy.ieumgil.domain.place.dto.PlaceResDTO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 추천 이유가 <b>없을 때 어떻게 비우는지</b>를 고정한다.
 *
 * <p>빈 문자열과 null 은 여기서 같은 값이 아니다. 모델에게 {@code reason: ""}은 "이유 칸이
 * 있는데 비었다"로 읽혀 그 칸을 스스로 채울 빌미가 되고, 그렇게 채운 문장은 우리가 근거를
 * 댈 수 없는 주장이 된다(평점·리뷰 데이터가 우리에게 없다). null 이어야 칸 자체가 없다.
 */
class PlaceSearchSummaryTest {

    private static PlaceResDTO.Place place() {
        return PlaceResDTO.Place.builder()
                .placeId("123").name("동백섬 로스터리").address("부산 해운대구")
                .lat(35.160).lng(129.160)
                .category("카페").categoryCode("CE7").categoryPath("음식점 > 카페")
                .build();
    }

    @Test
    @DisplayName("이유가 없으면 reason 은 null 이다 — 빈 문자열이면 모델이 그 칸을 지어내 채운다")
    void emptyReasonsBecomeNull() {
        PlaceSearchSummary summary = PlaceSearchSummary.from(place(), List.of());

        assertThat(summary.reason()).isNull();
    }

    @Test
    @DisplayName("이유가 여럿이면 ' · '로 잇는다")
    void multipleReasonsAreJoined() {
        PlaceSearchSummary summary = PlaceSearchSummary.from(place(),
                List.of("계획한 일정에서 도보 4분 거리", "체인점이 아닌 곳", "지금 일정에 없는 종류"));

        assertThat(summary.reason())
                .isEqualTo("계획한 일정에서 도보 4분 거리 · 체인점이 아닌 곳 · 지금 일정에 없는 종류");
    }

    @Test
    @DisplayName("이유를 계산하지 않는 호출부(일반 모드)도 reason 은 null 이다")
    void singleArgumentFactoryHasNoReason() {
        PlaceSearchSummary summary = PlaceSearchSummary.from(place());

        assertThat(summary.reason()).isNull();
    }

    @Test
    @DisplayName("이유를 더해도 나머지 필드는 그대로다 — 좌표는 여전히 넘기지 않는다")
    void keepsOtherFields() {
        PlaceSearchSummary summary = PlaceSearchSummary.from(place(), List.of("체인점이 아닌 곳"));

        assertThat(summary.name()).isEqualTo("동백섬 로스터리");
        assertThat(summary.address()).isEqualTo("부산 해운대구");
        assertThat(summary.category()).isEqualTo("카페");
        assertThat(summary.url()).isEqualTo("https://place.map.kakao.com/123");
    }
}
