package com.ssafy.ieumgil.domain.festival.service;

import com.ssafy.ieumgil.domain.festival.entity.Festival;
import com.ssafy.ieumgil.domain.festival.repository.FestivalRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;

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
}
