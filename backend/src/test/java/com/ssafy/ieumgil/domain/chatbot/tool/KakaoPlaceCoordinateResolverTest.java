package com.ssafy.ieumgil.domain.chatbot.tool;

import com.ssafy.ieumgil.domain.block.entity.Block;
import com.ssafy.ieumgil.domain.block.entity.BlockCategory;
import com.ssafy.ieumgil.domain.block.entity.BlockSource;
import org.junit.jupiter.api.DisplayName;
import com.ssafy.ieumgil.domain.place.dto.PlaceResDTO;
import com.ssafy.ieumgil.domain.place.service.PlaceQueryService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class KakaoPlaceCoordinateResolverTest {

    @Mock
    private PlaceQueryService placeQueryService;

    @Test
    void resolvesFirstSearchResult() {
        PlaceResDTO.Place first = PlaceResDTO.Place.builder()
                .placeId("111").name("성산일출봉").address("제주 서귀포시").lat(33.46).lng(126.94).category("관광명소")
                .build();
        PlaceResDTO.Place second = PlaceResDTO.Place.builder()
                .placeId("222").name("성산일출봉 매표소").address("제주 서귀포시").lat(33.46).lng(126.94).category("관광명소")
                .build();
        when(placeQueryService.searchPlaces(eq("제주도 성산일출봉"), isNull(), isNull()))
                .thenReturn(List.of(first, second));

        KakaoPlaceCoordinateResolver resolver = new KakaoPlaceCoordinateResolver(placeQueryService);

        Optional<PlaceResDTO.Place> result = resolver.resolve("제주도", "성산일출봉");

        assertThat(result).contains(first);
    }

    @Test
    void returnsEmptyWhenNoSearchResults() {
        when(placeQueryService.searchPlaces(eq("제주도 존재하지않는곳"), isNull(), isNull()))
                .thenReturn(List.of());

        KakaoPlaceCoordinateResolver resolver = new KakaoPlaceCoordinateResolver(placeQueryService);

        assertThat(resolver.resolve("제주도", "존재하지않는곳")).isEmpty();
    }

    private static Block boardBlock(String name, double lat, double lng) {
        return Block.builder()
                .startOffsetMinutes(0).orderKey("a0").name(name)
                .category(BlockCategory.SPOT).durationMin(60).budget(0)
                .lat(java.math.BigDecimal.valueOf(lat)).lng(java.math.BigDecimal.valueOf(lng))
                .placeId("b1").address("제주 서귀포시")
                .source(BlockSource.KAKAO)
                .build();
    }

    @Test
    @DisplayName("보드에 같은 이름의 블록이 있으면 그 좌표를 쓰고 카카오를 호출하지 않는다")
    void prefersBoardBlockCoordinatesOverKakaoSearch() {
        KakaoPlaceCoordinateResolver resolver = new KakaoPlaceCoordinateResolver(
                placeQueryService, () -> List.of(boardBlock("성산일출봉", 33.4581, 126.9425)));

        Optional<PlaceResDTO.Place> resolved = resolver.resolve("제주", "성산일출봉");

        assertThat(resolved).isPresent();
        assertThat(resolved.get().lat()).isEqualTo(33.4581);
        assertThat(resolved.get().lng()).isEqualTo(126.9425);
        verify(placeQueryService, never()).searchPlaces(anyString(), any(), any());
    }

    @Test
    @DisplayName("보드에 없는 장소는 기존대로 카카오로 폴백한다")
    void fallsBackToKakaoWhenNotOnBoard() {
        PlaceResDTO.Place found = PlaceResDTO.Place.builder()
                .placeId("k1").name("맛집").address("제주").lat(33.1).lng(126.1).category("음식점")
                .build();
        when(placeQueryService.searchPlaces("제주 맛집", null, null)).thenReturn(List.of(found));
        KakaoPlaceCoordinateResolver resolver = new KakaoPlaceCoordinateResolver(
                placeQueryService, () -> List.of(boardBlock("성산일출봉", 33.4581, 126.9425)));

        assertThat(resolver.resolve("제주", "맛집")).contains(found);
    }

    @Test
    @DisplayName("보드는 실제로 필요할 때만 한 번 읽는다 — 상시 조회하면 tool로 분리한 이유가 사라진다")
    void loadsBoardLazilyAndOnlyOnce() {
        java.util.concurrent.atomic.AtomicInteger loads = new java.util.concurrent.atomic.AtomicInteger();
        KakaoPlaceCoordinateResolver resolver = new KakaoPlaceCoordinateResolver(placeQueryService, () -> {
            loads.incrementAndGet();
            return List.of(boardBlock("성산일출봉", 33.4581, 126.9425));
        });

        assertThat(loads.get()).isZero();

        resolver.resolve("제주", "성산일출봉");
        resolver.resolve("제주", "성산일출봉");

        assertThat(loads.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("좌표 없는 보드 블록은 건너뛰고 카카오로 간다")
    void skipsBoardBlockWithoutCoordinates() {
        Block noCoords = Block.builder()
                .startOffsetMinutes(0).orderKey("a0").name("좌표없음")
                .category(BlockCategory.ETC).durationMin(60).budget(0)
                .source(BlockSource.MANUAL)
                .build();
        PlaceResDTO.Place found = PlaceResDTO.Place.builder()
                .placeId("k1").name("좌표없음").address("제주").lat(33.1).lng(126.1).category("관광명소")
                .build();
        when(placeQueryService.searchPlaces("제주 좌표없음", null, null)).thenReturn(List.of(found));
        KakaoPlaceCoordinateResolver resolver = new KakaoPlaceCoordinateResolver(
                placeQueryService, () -> List.of(noCoords));

        assertThat(resolver.resolve("제주", "좌표없음")).contains(found);
    }
}
