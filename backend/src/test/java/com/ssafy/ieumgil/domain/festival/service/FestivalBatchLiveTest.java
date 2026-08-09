package com.ssafy.ieumgil.domain.festival.service;

import com.ssafy.ieumgil.domain.festival.repository.FestivalRepository;
import com.ssafy.ieumgil.support.IntegrationTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 배치가 TourAPI 총건수만큼 실제로 적재하는지 확인한다.
 *
 * <p>예전 구조는 "빈 페이지가 오면 끝"으로 판단하면서 페이지 조회 예외를 잡지 않아, 중간 한
 * 페이지가 실패하면 배치 전체가 조용히 중단됐다. 그래서 209건 중 100건만 적재된 상태였고
 * 로그에는 "100건 수집"이 정상처럼 찍혔다. 실제 API로 완주를 확인하는 것이 이 테스트의 목적이다.
 */
// 부모 IntegrationTestSupport가 tourapi.service-key를 비워 배치 bean을 끄지만, 이 live 테스트는
// 실 API 완주가 목적이므로 키를 다시 공급해 bean을 되살린다.
@Tag("live")
@TestPropertySource(properties = "tourapi.service-key=${TOUR_API_KEY:}")
class FestivalBatchLiveTest extends IntegrationTestSupport {

    @Autowired
    private FestivalBatchService festivalBatchService;

    @Autowired
    private FestivalRepository festivalRepository;

    @Test
    @DisplayName("TourAPI 총건수만큼 전량 적재한다")
    void collectsEveryPage() {
        FestivalBatchService.SyncResult result = festivalBatchService.syncFestivals();

        System.out.println("=== 총 " + result.expected() + "건 / 적재 " + result.collected() + "건 ===");

        assertThat(result.expected()).isGreaterThan(100);
        assertThat(result.complete()).isTrue();
        assertThat(festivalRepository.count()).isGreaterThanOrEqualTo(result.expected());
    }
}
