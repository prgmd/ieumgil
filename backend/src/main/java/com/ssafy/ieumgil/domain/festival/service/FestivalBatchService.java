package com.ssafy.ieumgil.domain.festival.service;

import com.ssafy.ieumgil.domain.festival.client.TourApiClient;
import com.ssafy.ieumgil.domain.festival.dto.TourApiResponse;
import com.ssafy.ieumgil.domain.festival.entity.Festival;
import com.ssafy.ieumgil.domain.festival.repository.FestivalRepository;
import com.ssafy.ieumgil.domain.festival.util.HomepageUrlExtractor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.CompletableFuture;

@Slf4j
@Component
// serviceKey가 없으면(테스트 컨텍스트·키 미설정 환경) 배치 자체를 띄우지 않는다. 안 그러면
// 기동 시 ApplicationReadyEvent가 발화해 실 TourAPI 전량 수집이 나간다 — OpinetFuelPriceProvider와 같은 방어.
@ConditionalOnExpression("!'${tourapi.service-key:}'.isBlank()")
@RequiredArgsConstructor
public class FestivalBatchService {

    private static final int PAGE_SIZE = 100;

    /**
     * 한 번의 동기화가 도는 최대 페이지 수(= 1만 건).
     *
     * <p>실제 총건수는 209건(2페이지) 규모라 50배 여유다. 상한이 없으면 TourAPI가 비정상적인
     * totalCount를 한 번 뱉는 것만으로 배치가 그 수만큼 페이지를 돌고, {@code @Scheduled}는
     * 기본이 단일 스레드라 그동안 다른 스케줄 작업까지 밀린다.
     */
    private static final int MAX_PAGES = 100;

    private final TourApiClient tourApiClient;
    private final FestivalRepository festivalRepository;

    /**
     * @param expected  TourAPI가 보고한 총건수
     * @param collected 실제로 적재한 건수
     */
    public record SyncResult(int expected, int collected) {

        /** 총건수만큼 다 받았는가. 부족하게 끝났으면 다음 실행에서 메꿔지길 기대할 것이 아니라 원인을 봐야 한다. */
        public boolean complete() {
            return expected > 0 && collected >= expected;
        }
    }

    /**
     * TourAPI 축제 전량 동기화.
     *
     * <p>총건수를 먼저 받아 페이지 수를 정한다. 예전에는 "빈 페이지가 오면 끝"으로 판단하면서
     * 페이지 조회 예외를 잡지 않아, 중간 한 페이지가 실패하면 <b>배치 전체가 조용히 중단</b>됐다.
     * 실제로 그래서 209건 중 100건(1페이지)만 적재된 상태였고, 로그에는 "100건 수집"이 정상처럼
     * 찍혀 아무도 몰랐다.
     *
     * <p>이제 페이지별로 실패를 흡수해 남은 페이지를 계속 시도하고, 총건수와 적재 건수를 비교해
     * 부족하면 경고한다. 총건수 자체를 못 구하면 아무것도 수집하지 않는다 — 몇 페이지를 돌아야
     * 하는지 모르는 상태로 덜 긁고 성공처럼 끝내는 것이 지금까지의 문제였다.
     */
    @Scheduled(cron = "0 0 4 * * *")
    public SyncResult syncFestivals() {
        LocalDate now = LocalDate.now();
        String today = now.format(DateTimeFormatter.BASIC_ISO_DATE);

        int expected;
        try {
            expected = tourApiClient.fetchTotalCount(today);
        } catch (RuntimeException e) {
            log.error("TourAPI 총건수 조회 실패 — 이번 동기화를 건너뛴다", e);
            return new SyncResult(0, 0);
        }
        if (expected <= 0) {
            log.warn("TourAPI 총건수가 {}건이라 수집할 것이 없다", expected);
            return new SyncResult(expected, 0);
        }

        int pageCount = (expected + PAGE_SIZE - 1) / PAGE_SIZE;
        if (pageCount > MAX_PAGES) {
            log.error("TourAPI 총건수가 {}건이라 {}페이지가 필요하다 — 상한 {}페이지까지만 돈다. 응답이 이상한지 확인할 것",
                    expected, pageCount, MAX_PAGES);
            pageCount = MAX_PAGES;
        }
        int collected = 0;
        for (int pageNo = 1; pageNo <= pageCount; pageNo++) {
            collected += collectPage(today, pageNo);
        }

        // 수집이 끝난 뒤에 지난 축제를 정리한다 — 방금 upsert한 축제는 종료일이 오늘 이상이라
        // 지워지지 않는다. 먼저 정리하면 아직 수집 전인 상태를 잘못 지울 수 있어 순서가 중요하다.
        int removed = festivalRepository.deleteExpiredBefore(now);
        log.info("지난 축제 {}건 정리 완료", removed);

        SyncResult result = new SyncResult(expected, collected);
        if (result.complete()) {
            log.info("TourAPI 축제 {}건 수집 완료", collected);
        } else {
            log.warn("TourAPI 축제 수집 부족 — 총 {}건 중 {}건만 적재됐다", expected, collected);
        }
        return result;
    }

    /**
     * 기동 직후 축제 데이터가 비어 있으면 한 번 채운다.
     *
     * <p>개발 노트북은 새벽에 꺼져 있어 04:00 크론이 사실상 돌지 않는다 — 실제로 축제가 빈 채로
     * 며칠 방치됐다. 그래서 기동 시 한 번 확인해, 비어 있을 때만 수집한다. 이미 차 있으면 재수집하지
     * 않는다({@code count()>0} 스킵) — 매 재시작마다 전량 재수집하는 낭비를 막는다.
     *
     * <p>{@code syncFestivals()}는 N페이지 TourAPI 배치라 오래 걸린다. {@link ApplicationReadyEvent}
     * 스레드에서 그대로 돌리면 기동이 그만큼 지연되므로 별도 스레드로 분리한다. 프로젝트에 아직
     * {@code @EnableAsync}·비동기 사용처가 없어, 전역 설정을 새로 들이는 대신 {@link CompletableFuture}로
     * 이 메서드 안에서만 분리했다. 가드({@code count()}) 판단은 동기로 하고, 무거운 수집만 비동기로 넘긴다.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void syncOnStartupIfEmpty() {
        if (festivalRepository.count() > 0) {
            log.info("축제 데이터 존재 — 기동 동기화 스킵");
            return;
        }
        log.info("축제 데이터 비어있음 — 기동 동기화 시작");
        // 별 스레드라 예외가 future에 갇혀 조용히 사라지면 빈 DB로 방치된다 — 실패를 로그로 드러낸다.
        CompletableFuture.runAsync(this::syncFestivals)
                .whenComplete((result, e) -> {
                    if (e != null) log.error("기동 축제 동기화 실패", e);
                });
    }

    /** 한 페이지의 실패는 그 페이지에서 끝낸다 — 남은 페이지는 계속 시도해야 한다. */
    private int collectPage(String eventStartDate, int pageNo) {
        List<TourApiResponse.Item> items;
        try {
            items = tourApiClient.searchFestivals(eventStartDate, pageNo, PAGE_SIZE);
        } catch (RuntimeException e) {
            log.warn("TourAPI {}페이지 조회 실패 — 이 페이지를 건너뛴다: {}", pageNo, e.getMessage());
            return 0;
        }

        int collected = 0;
        for (TourApiResponse.Item item : items) {
            try {
                upsert(item);
                collected++;
            } catch (RuntimeException e) {
                log.warn("축제 적재 실패 contentId={}: {}", item.contentid(), e.getMessage());
            }
        }
        return collected;
    }

    private void upsert(TourApiResponse.Item item) {
        LocalDate eventStartDate = LocalDate.parse(item.eventstartdate(), DateTimeFormatter.BASIC_ISO_DATE);
        LocalDate eventEndDate = LocalDate.parse(item.eventenddate(), DateTimeFormatter.BASIC_ISO_DATE);
        Double lat = item.mapy() == null || item.mapy().isBlank() ? null : Double.parseDouble(item.mapy());
        Double lng = item.mapx() == null || item.mapx().isBlank() ? null : Double.parseDouble(item.mapx());
        String addr = (item.addr1() == null ? "" : item.addr1())
                + (item.addr2() == null || item.addr2().isBlank() ? "" : " " + item.addr2());
        String homepage = fetchHomepageQuietly(item.contentid());

        Festival festival = festivalRepository.findByContentId(item.contentid())
                .orElseGet(() -> Festival.builder().contentId(item.contentid()).build());
        festival.update(item.title(), item.lclsSystm2(), item.lDongRegnCd(), item.lDongSignguCd(),
                addr, lat, lng, eventStartDate, eventEndDate, item.firstimage(), homepage);
        festivalRepository.save(festival);
    }

    /** 홈페이지는 부가 정보다 — 상세 조회가 실패해도 축제 적재를 막지 않는다. */
    private String fetchHomepageQuietly(String contentId) {
        try {
            return HomepageUrlExtractor.extract(tourApiClient.fetchDetailHomepage(contentId));
        } catch (RuntimeException e) {
            log.warn("축제 상세 조회 실패 — homepage 없이 적재 contentId={}: {}", contentId, e.getMessage());
            return null;
        }
    }
}
