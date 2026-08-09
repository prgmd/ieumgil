package com.ssafy.ieumgil.domain.festival.service;

import com.ssafy.ieumgil.domain.festival.entity.Festival;
import com.ssafy.ieumgil.domain.festival.repository.FestivalRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FestivalQueryServiceTest {

    @Mock
    private FestivalRepository festivalRepository;

    private FestivalQueryService festivalQueryService;

    @Test
    void delegatesToRepositoryOverlapQuery() {
        festivalQueryService = new FestivalQueryServiceImpl(festivalRepository);
        Festival festival = Festival.builder()
                .contentId("1").title("축제").category("EV01")
                .lDongRegnCd("39").lDongSignguCd("000").addr("제주")
                .lat(33.4).lng(126.5)
                .eventStartDate(LocalDate.of(2026, 8, 1)).eventEndDate(LocalDate.of(2026, 8, 5))
                .build();
        when(festivalRepository.findOverlapping("39", LocalDate.of(2026, 8, 2), LocalDate.of(2026, 8, 3)))
                .thenReturn(List.of(festival));

        List<Festival> result = festivalQueryService.findByRegionAndDateRange(
                "39", LocalDate.of(2026, 8, 2), LocalDate.of(2026, 8, 3));

        assertThat(result).containsExactly(festival);
    }

    @Test
    @DisplayName("저장된 homepage를 준다")
    void 저장된_홈페이지를_읽는다() {
        festivalQueryService = new FestivalQueryServiceImpl(festivalRepository);
        Festival festival = Festival.builder().contentId("123").homepage("http://mud.example.com").build();
        when(festivalRepository.findByContentId("123")).thenReturn(Optional.of(festival));

        assertThat(festivalQueryService.getHomepageUrl("123")).isEqualTo("http://mud.example.com");
    }

    @Test
    @DisplayName("축제가 없거나 homepage가 null이면 null을 준다")
    void 없으면_null() {
        festivalQueryService = new FestivalQueryServiceImpl(festivalRepository);
        when(festivalRepository.findByContentId("999")).thenReturn(Optional.empty());

        assertThat(festivalQueryService.getHomepageUrl("999")).isNull();
    }
}
