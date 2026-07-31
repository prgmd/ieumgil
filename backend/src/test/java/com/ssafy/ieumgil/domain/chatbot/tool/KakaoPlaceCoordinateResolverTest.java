package com.ssafy.ieumgil.domain.chatbot.tool;

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
}
