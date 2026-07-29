package com.ssafy.ieumgil.domain.festival.service;

import com.ssafy.ieumgil.domain.festival.client.TourApiClient;
import com.ssafy.ieumgil.domain.festival.dto.TourApiResponse;
import com.ssafy.ieumgil.domain.festival.entity.Festival;
import com.ssafy.ieumgil.domain.festival.repository.FestivalRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FestivalBatchServiceTest {

    @Mock
    private TourApiClient tourApiClient;

    @Mock
    private FestivalRepository festivalRepository;

    private FestivalBatchService festivalBatchService;

    @Test
    void newContentIdIsInserted() {
        festivalBatchService = new FestivalBatchService(tourApiClient, festivalRepository);
        TourApiResponse.Item item = new TourApiResponse.Item(
                "new-1", "새 축제", "서울 강동구", "", "127.13", "37.55",
                "20260801", "20260803", "http://image", "11", "140", "EV01");
        when(tourApiClient.searchFestivals(anyString(), anyInt(), anyInt())).thenReturn(List.of(item));
        when(festivalRepository.findByContentId("new-1")).thenReturn(Optional.empty());

        festivalBatchService.syncFestivals();

        ArgumentCaptor<Festival> captor = ArgumentCaptor.forClass(Festival.class);
        verify(festivalRepository).save(captor.capture());
        Festival saved = captor.getValue();
        assertThat(saved.getContentId()).isEqualTo("new-1");
        assertThat(saved.getTitle()).isEqualTo("새 축제");
        assertThat(saved.getEventStartDate()).isEqualTo(LocalDate.of(2026, 8, 1));
        assertThat(saved.getEventEndDate()).isEqualTo(LocalDate.of(2026, 8, 3));
        assertThat(saved.getLat()).isEqualTo(37.55);
        assertThat(saved.getLng()).isEqualTo(127.13);
    }

    @Test
    void existingContentIdIsUpdatedNotDuplicated() {
        festivalBatchService = new FestivalBatchService(tourApiClient, festivalRepository);
        TourApiResponse.Item item = new TourApiResponse.Item(
                "existing-1", "제목 바뀜", "주소", "", "127.0", "37.0",
                "20260801", "20260803", "http://image", "11", "140", "EV01");
        Festival existing = Festival.builder()
                .contentId("existing-1").title("옛 제목").category("EV01")
                .lDongRegnCd("11").lDongSignguCd("140").addr("옛 주소")
                .lat(37.0).lng(127.0)
                .eventStartDate(LocalDate.of(2026, 8, 1)).eventEndDate(LocalDate.of(2026, 8, 3))
                .build();
        when(tourApiClient.searchFestivals(anyString(), anyInt(), anyInt())).thenReturn(List.of(item));
        when(festivalRepository.findByContentId("existing-1")).thenReturn(Optional.of(existing));

        festivalBatchService.syncFestivals();

        ArgumentCaptor<Festival> captor = ArgumentCaptor.forClass(Festival.class);
        verify(festivalRepository).save(captor.capture());
        assertThat(captor.getValue().getTitle()).isEqualTo("제목 바뀜");
        assertThat(captor.getValue()).isSameAs(existing);
    }

    @Test
    void fullPageTriggersNextPageFetch() {
        festivalBatchService = new FestivalBatchService(tourApiClient, festivalRepository);
        List<TourApiResponse.Item> firstPage = IntStream.rangeClosed(1, 100)
                .mapToObj(i -> new TourApiResponse.Item(
                        "page1-" + i, "축제" + i, "주소", "", "127.0", "37.0",
                        "20260801", "20260803", "http://image", "11", "140", "EV01"))
                .toList();
        List<TourApiResponse.Item> secondPage = List.of(new TourApiResponse.Item(
                "page2-1", "축제 101", "주소", "", "127.0", "37.0",
                "20260801", "20260803", "http://image", "11", "140", "EV01"));
        when(tourApiClient.searchFestivals(anyString(), eq(1), eq(100))).thenReturn(firstPage);
        when(tourApiClient.searchFestivals(anyString(), eq(2), eq(100))).thenReturn(secondPage);
        when(festivalRepository.findByContentId(anyString())).thenReturn(Optional.empty());

        festivalBatchService.syncFestivals();

        verify(tourApiClient).searchFestivals(anyString(), eq(1), eq(100));
        verify(tourApiClient).searchFestivals(anyString(), eq(2), eq(100));
        verify(festivalRepository, times(101)).save(any());
    }

    @Test
    void oneMalformedItemDoesNotStopOthersInThePage() {
        festivalBatchService = new FestivalBatchService(tourApiClient, festivalRepository);
        TourApiResponse.Item malformed = new TourApiResponse.Item(
                "bad-1", "잘못된 날짜", "주소", "", "127.0", "37.0",
                "invalid-date", "20260803", "http://image", "11", "140", "EV01");
        TourApiResponse.Item valid = new TourApiResponse.Item(
                "good-1", "정상 축제", "주소", "", "127.0", "37.0",
                "20260801", "20260803", "http://image", "11", "140", "EV01");
        List<TourApiResponse.Item> page = new ArrayList<>(List.of(malformed, valid));
        when(tourApiClient.searchFestivals(anyString(), anyInt(), anyInt())).thenReturn(page);
        when(festivalRepository.findByContentId(anyString())).thenReturn(Optional.empty());

        festivalBatchService.syncFestivals();

        ArgumentCaptor<Festival> captor = ArgumentCaptor.forClass(Festival.class);
        verify(festivalRepository).save(captor.capture());
        assertThat(captor.getValue().getContentId()).isEqualTo("good-1");
    }
}
