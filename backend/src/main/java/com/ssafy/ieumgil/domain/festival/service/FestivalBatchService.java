package com.ssafy.ieumgil.domain.festival.service;

import com.ssafy.ieumgil.domain.festival.client.TourApiClient;
import com.ssafy.ieumgil.domain.festival.dto.TourApiResponse;
import com.ssafy.ieumgil.domain.festival.entity.Festival;
import com.ssafy.ieumgil.domain.festival.repository.FestivalRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

@Slf4j
@Component
@RequiredArgsConstructor
public class FestivalBatchService {

    private static final int PAGE_SIZE = 100;

    private final TourApiClient tourApiClient;
    private final FestivalRepository festivalRepository;

    @Scheduled(cron = "0 0 4 * * *")
    public void syncFestivals() {
        String today = LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE);
        int pageNo = 1;
        int collected = 0;
        var items = tourApiClient.searchFestivals(today, pageNo, PAGE_SIZE);
        while (!items.isEmpty()) {
            for (TourApiResponse.Item item : items) {
                try {
                    upsert(item);
                    collected++;
                } catch (RuntimeException e) {
                    log.warn("축제 적재 실패 contentId={}: {}", item.contentid(), e.getMessage());
                }
            }
            if (items.size() < PAGE_SIZE) {
                break;
            }
            pageNo++;
            items = tourApiClient.searchFestivals(today, pageNo, PAGE_SIZE);
        }
        log.info("TourAPI 축제 {}건 수집", collected);
    }

    private void upsert(TourApiResponse.Item item) {
        LocalDate eventStartDate = LocalDate.parse(item.eventstartdate(), DateTimeFormatter.BASIC_ISO_DATE);
        LocalDate eventEndDate = LocalDate.parse(item.eventenddate(), DateTimeFormatter.BASIC_ISO_DATE);
        Double lat = item.mapy() == null || item.mapy().isBlank() ? null : Double.parseDouble(item.mapy());
        Double lng = item.mapx() == null || item.mapx().isBlank() ? null : Double.parseDouble(item.mapx());
        String addr = (item.addr1() == null ? "" : item.addr1())
                + (item.addr2() == null || item.addr2().isBlank() ? "" : " " + item.addr2());

        Festival festival = festivalRepository.findByContentId(item.contentid())
                .orElseGet(() -> Festival.builder().contentId(item.contentid()).build());
        festival.update(item.title(), item.lclsSystm2(), item.lDongRegnCd(), item.lDongSignguCd(),
                addr, lat, lng, eventStartDate, eventEndDate, item.firstimage());
        festivalRepository.save(festival);
    }
}
