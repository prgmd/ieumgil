package com.ssafy.ieumgil.domain.festival.service;

import com.ssafy.ieumgil.domain.festival.client.TourApiClient;
import com.ssafy.ieumgil.domain.festival.dto.TourApiResponse;
import com.ssafy.ieumgil.domain.festival.entity.Festival;
import com.ssafy.ieumgil.domain.festival.repository.FestivalRepository;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
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
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.timeout;
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
        when(tourApiClient.fetchTotalCount(anyString())).thenReturn(1);
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
        when(tourApiClient.fetchTotalCount(anyString())).thenReturn(1);
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
        when(tourApiClient.fetchTotalCount(anyString())).thenReturn(150);
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
        when(tourApiClient.fetchTotalCount(anyString())).thenReturn(page.size());
        when(tourApiClient.searchFestivals(anyString(), anyInt(), anyInt())).thenReturn(page);
        when(festivalRepository.findByContentId(anyString())).thenReturn(Optional.empty());

        festivalBatchService.syncFestivals();

        ArgumentCaptor<Festival> captor = ArgumentCaptor.forClass(Festival.class);
        verify(festivalRepository).save(captor.capture());
        assertThat(captor.getValue().getContentId()).isEqualTo("good-1");
    }

    @Test
    @DisplayName("배치가 detailCommon2 homepage를 추출해 저장한다")
    void 홈페이지를_채운다() {
        festivalBatchService = new FestivalBatchService(tourApiClient, festivalRepository);
        TourApiResponse.Item item = new TourApiResponse.Item(
                "123", "머드축제", "보령시", "", "126.5", "36.3",
                "20260701", "20260710", "http://img", "44", "44130", "축제");
        when(tourApiClient.fetchTotalCount(anyString())).thenReturn(1);
        when(tourApiClient.searchFestivals(anyString(), eq(1), anyInt())).thenReturn(List.of(item));
        when(festivalRepository.findByContentId("123")).thenReturn(Optional.empty());
        when(tourApiClient.fetchDetailHomepage("123"))
                .thenReturn("<a href=\"http://mud.example.com\">머드</a>");

        festivalBatchService.syncFestivals();

        ArgumentCaptor<Festival> captor = ArgumentCaptor.forClass(Festival.class);
        verify(festivalRepository).save(captor.capture());
        assertThat(captor.getValue().getHomepage()).isEqualTo("http://mud.example.com");
    }

    @Test
    @DisplayName("상세 조회가 실패해도 축제는 homepage=null로 저장된다")
    void 상세_실패해도_축제는_적재된다() {
        festivalBatchService = new FestivalBatchService(tourApiClient, festivalRepository);
        TourApiResponse.Item item = new TourApiResponse.Item(
                "123", "머드축제", "보령시", "", "126.5", "36.3",
                "20260701", "20260710", "http://img", "44", "44130", "축제");
        when(tourApiClient.fetchTotalCount(anyString())).thenReturn(1);
        when(tourApiClient.searchFestivals(anyString(), eq(1), anyInt())).thenReturn(List.of(item));
        when(festivalRepository.findByContentId("123")).thenReturn(Optional.empty());
        when(tourApiClient.fetchDetailHomepage("123"))
                .thenThrow(new com.ssafy.ieumgil.domain.festival.exception.FestivalException(
                        com.ssafy.ieumgil.domain.festival.exception.FestivalErrorCode.TOUR_API_CALL_FAILED));

        festivalBatchService.syncFestivals();

        ArgumentCaptor<Festival> captor = ArgumentCaptor.forClass(Festival.class);
        verify(festivalRepository).save(captor.capture());
        assertThat(captor.getValue().getHomepage()).isNull();
    }

    @Test
    @DisplayName("기존 행에 homepage가 아직 없으면 재싱크에서 상세를 조회해 채운다")
    void 재싱크_기존행_홈페이지_비어있으면_상세_조회해_채운다() {
        festivalBatchService = new FestivalBatchService(tourApiClient, festivalRepository);
        TourApiResponse.Item item = new TourApiResponse.Item(
                "123", "머드축제", "보령시", "", "126.5", "36.3",
                "20260701", "20260710", "http://img", "44", "44130", "축제");
        // 지난번 상세 조회 실패 등으로 homepage가 비어 있는 기존 행
        Festival existing = Festival.builder()
                .contentId("123").title("머드축제").category("축제")
                .lDongRegnCd("44").lDongSignguCd("44130").addr("보령시")
                .lat(36.3).lng(126.5)
                .eventStartDate(LocalDate.of(2026, 7, 1)).eventEndDate(LocalDate.of(2026, 7, 10))
                .build();
        when(tourApiClient.fetchTotalCount(anyString())).thenReturn(1);
        when(tourApiClient.searchFestivals(anyString(), eq(1), anyInt())).thenReturn(List.of(item));
        when(festivalRepository.findByContentId("123")).thenReturn(Optional.of(existing));
        when(tourApiClient.fetchDetailHomepage("123"))
                .thenReturn("<a href=\"http://mud.example.com\">머드</a>");

        festivalBatchService.syncFestivals();

        verify(tourApiClient).fetchDetailHomepage("123");
        ArgumentCaptor<Festival> captor = ArgumentCaptor.forClass(Festival.class);
        verify(festivalRepository).save(captor.capture());
        assertThat(captor.getValue().getHomepage()).isEqualTo("http://mud.example.com");
    }

    @Test
    @DisplayName("이미 homepage가 저장돼 있으면 재싱크에서 상세를 다시 조회하지 않는다")
    void 재싱크_저장된_홈페이지_있으면_상세_조회_스킵() {
        festivalBatchService = new FestivalBatchService(tourApiClient, festivalRepository);
        TourApiResponse.Item item = new TourApiResponse.Item(
                "123", "머드축제", "보령시", "", "126.5", "36.3",
                "20260701", "20260710", "http://img", "44", "44130", "축제");
        Festival existing = Festival.builder()
                .contentId("123").title("머드축제").category("축제")
                .lDongRegnCd("44").lDongSignguCd("44130").addr("보령시")
                .lat(36.3).lng(126.5)
                .eventStartDate(LocalDate.of(2026, 7, 1)).eventEndDate(LocalDate.of(2026, 7, 10))
                .homepage("http://mud.example.com")
                .build();
        when(tourApiClient.fetchTotalCount(anyString())).thenReturn(1);
        when(tourApiClient.searchFestivals(anyString(), eq(1), anyInt())).thenReturn(List.of(item));
        when(festivalRepository.findByContentId("123")).thenReturn(Optional.of(existing));

        festivalBatchService.syncFestivals();

        // 저장된 homepage가 있으니 상세 HTTP를 아예 부르지 않는다 — 야간 sync 낭비 제거의 핵심
        verify(tourApiClient, never()).fetchDetailHomepage(anyString());
        ArgumentCaptor<Festival> captor = ArgumentCaptor.forClass(Festival.class);
        verify(festivalRepository).save(captor.capture());
        assertThat(captor.getValue().getHomepage()).isEqualTo("http://mud.example.com");
    }

    @Test
    @DisplayName("총건수 기준으로 페이지 수를 정하고 끝까지 돈다")
    void paginatesByTotalCount() {
        festivalBatchService = new FestivalBatchService(tourApiClient, festivalRepository);
        when(tourApiClient.fetchTotalCount(anyString())).thenReturn(209);
        when(tourApiClient.searchFestivals(anyString(), anyInt(), anyInt()))
                .thenAnswer(inv -> page(100));
        when(festivalRepository.findByContentId(anyString())).thenReturn(Optional.empty());

        FestivalBatchService.SyncResult result = festivalBatchService.syncFestivals();

        // 209건이면 100씩 3페이지
        verify(tourApiClient).searchFestivals(anyString(), eq(1), eq(100));
        verify(tourApiClient).searchFestivals(anyString(), eq(2), eq(100));
        verify(tourApiClient).searchFestivals(anyString(), eq(3), eq(100));
        assertThat(result.expected()).isEqualTo(209);
    }

    @Test
    @DisplayName("한 페이지 조회가 실패해도 나머지 페이지를 계속 수집한다 — 예전엔 배치 전체가 조용히 중단됐다")
    void pageFailureDoesNotAbortWholeRun() {
        festivalBatchService = new FestivalBatchService(tourApiClient, festivalRepository);
        when(tourApiClient.fetchTotalCount(anyString())).thenReturn(250);
        when(tourApiClient.searchFestivals(anyString(), eq(1), eq(100))).thenReturn(page(100));
        when(tourApiClient.searchFestivals(anyString(), eq(2), eq(100)))
                .thenThrow(new RuntimeException("일시 장애"));
        when(tourApiClient.searchFestivals(anyString(), eq(3), eq(100))).thenReturn(page(50));
        when(festivalRepository.findByContentId(anyString())).thenReturn(Optional.empty());

        FestivalBatchService.SyncResult result = festivalBatchService.syncFestivals();

        // 2페이지가 죽어도 3페이지는 수집된다
        verify(tourApiClient).searchFestivals(anyString(), eq(3), eq(100));
        assertThat(result.collected()).isEqualTo(150);
        assertThat(result.expected()).isEqualTo(250);
        // 부족하게 끝났음을 결과로 알 수 있어야 한다 — 예전엔 "N건 수집" 로그만 남아 아무도 몰랐다
        assertThat(result.complete()).isFalse();
    }

    @Test
    @DisplayName("총건수를 못 구하면 수집하지 않는다 — 덜 긁고 성공처럼 끝내지 않는다")
    void unknownTotalCountSkipsRun() {
        festivalBatchService = new FestivalBatchService(tourApiClient, festivalRepository);
        when(tourApiClient.fetchTotalCount(anyString())).thenThrow(new RuntimeException("장애"));

        FestivalBatchService.SyncResult result = festivalBatchService.syncFestivals();

        assertThat(result.collected()).isZero();
        assertThat(result.complete()).isFalse();
        verify(tourApiClient, org.mockito.Mockito.never()).searchFestivals(anyString(), anyInt(), anyInt());
    }

    @Test
    @DisplayName("총건수가 비정상적으로 크면 상한까지만 돈다 — 스케줄러 스레드를 몇 시간씩 붙잡지 않는다")
    void absurdTotalCountIsCappedAtMaxPages() {
        festivalBatchService = new FestivalBatchService(tourApiClient, festivalRepository);
        when(tourApiClient.fetchTotalCount(anyString())).thenReturn(1_000_000);
        when(tourApiClient.searchFestivals(anyString(), anyInt(), anyInt())).thenReturn(List.of());

        Logger logger = (Logger) LoggerFactory.getLogger(FestivalBatchService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        FestivalBatchService.SyncResult result;
        try {
            result = festivalBatchService.syncFestivals();
        } finally {
            logger.detachAppender(appender);
        }

        // 1,000,000건이면 10,000페이지다. 상한(100페이지)까지만 돌아야 한다.
        verify(tourApiClient, times(100)).searchFestivals(anyString(), anyInt(), anyInt());
        assertThat(result.complete()).isFalse();
        // 조용히 잘리면 다음에 또 같은 일이 난다 — 에러로 드러나야 한다
        assertThat(appender.list).anySatisfy(event -> assertThat(event.getLevel()).isEqualTo(Level.ERROR));
    }

    @Test
    @DisplayName("수집이 끝난 뒤에 지난 축제를 정리한다 — 방금 적재한 축제가 지워지면 안 된다")
    void cleansUpExpiredFestivalsAfterCollecting() {
        festivalBatchService = new FestivalBatchService(tourApiClient, festivalRepository);
        TourApiResponse.Item item = new TourApiResponse.Item(
                "new-1", "새 축제", "서울 강동구", "", "127.13", "37.55",
                "20260801", "20260803", "http://image", "11", "140", "EV01");
        when(tourApiClient.fetchTotalCount(anyString())).thenReturn(1);
        when(tourApiClient.searchFestivals(anyString(), anyInt(), anyInt())).thenReturn(List.of(item));
        when(festivalRepository.findByContentId("new-1")).thenReturn(Optional.empty());

        festivalBatchService.syncFestivals();

        InOrder inOrder = inOrder(festivalRepository);
        inOrder.verify(festivalRepository).save(any());
        inOrder.verify(festivalRepository).deleteExpiredBefore(any(LocalDate.class));
    }

    @Test
    @DisplayName("축제 데이터가 있으면 기동 동기화를 스킵한다 — 찬 DB를 재수집하지 않는다")
    void 데이터가_있으면_기동_동기화를_스킵한다() {
        FestivalBatchService spy = spy(new FestivalBatchService(tourApiClient, festivalRepository));
        when(festivalRepository.count()).thenReturn(5L);

        spy.syncOnStartupIfEmpty();

        verify(spy, never()).syncFestivals();
        verify(tourApiClient, never()).fetchTotalCount(anyString());
    }

    @Test
    @DisplayName("축제 데이터가 비어 있으면 기동 시 수집을 실행한다")
    void 데이터가_비어있으면_기동_동기화를_실행한다() {
        FestivalBatchService spy = spy(new FestivalBatchService(tourApiClient, festivalRepository));
        when(festivalRepository.count()).thenReturn(0L);
        // 총건수 0을 주면 syncFestivals가 실호출(searchFestivals) 없이 조기 종료한다 — live를 타지 않는다
        when(tourApiClient.fetchTotalCount(anyString())).thenReturn(0);

        spy.syncOnStartupIfEmpty();

        // 수집은 CompletableFuture로 비동기 실행되므로 진입을 timeout으로 기다린다
        verify(tourApiClient, timeout(2000)).fetchTotalCount(anyString());
        verify(spy).syncFestivals();
    }

    private List<TourApiResponse.Item> page(int size) {
        List<TourApiResponse.Item> items = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            items.add(new TourApiResponse.Item(
                    "c" + java.util.UUID.randomUUID(), "축제", "서울시", "", "127.0", "37.5",
                    "20261001", "20261003", null, "11", "11110", "EV01"));
        }
        return items;
    }
}
