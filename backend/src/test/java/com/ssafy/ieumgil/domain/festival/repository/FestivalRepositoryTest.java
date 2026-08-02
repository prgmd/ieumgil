package com.ssafy.ieumgil.domain.festival.repository;

import com.ssafy.ieumgil.domain.festival.entity.Festival;
import com.ssafy.ieumgil.support.IntegrationTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 다른 JPA 테스트들과 같이 IntegrationTestSupport(Testcontainers)를 상속한다.
 *
 * <p>예전에는 {@code @DataJpaTest @AutoConfigureTestDatabase(replace = NONE)}이라 외부 DB
 * 접속 정보가 환경에 있어야만 컨텍스트가 떴다. 그래서 로컬 gradle 실행에서
 * "URL must start with 'jdbc'"로 계속 실패했다 — .env 없는 CI나 다른 팀원 PC에서도 같다.
 */
class FestivalRepositoryTest extends IntegrationTestSupport {

    @Autowired
    private FestivalRepository festivalRepository;

    @Test
    void findByContentIdReturnsSavedFestival() {
        Festival festival = Festival.builder()
                .contentId("test-content-1")
                .title("테스트 축제")
                .category("EV01")
                .lDongRegnCd("11")
                .lDongSignguCd("140")
                .addr("서울특별시 강동구")
                .lat(37.55)
                .lng(127.13)
                .eventStartDate(LocalDate.of(2026, 8, 1))
                .eventEndDate(LocalDate.of(2026, 8, 3))
                .build();
        festivalRepository.save(festival);

        Optional<Festival> found = festivalRepository.findByContentId("test-content-1");

        assertThat(found).isPresent();
        assertThat(found.get().getTitle()).isEqualTo("테스트 축제");
    }

    @Test
    void findOverlappingReturnsOnlyMatchingRegionAndDateRange() {
        festivalRepository.save(Festival.builder()
                .contentId("overlap-in-range").title("기간 겹침").category("EV01")
                .lDongRegnCd("39").lDongSignguCd("000").addr("제주")
                .lat(33.4).lng(126.5)
                .eventStartDate(LocalDate.of(2026, 8, 1)).eventEndDate(LocalDate.of(2026, 8, 5))
                .build());
        festivalRepository.save(Festival.builder()
                .contentId("out-of-range").title("기간 안 겹침").category("EV01")
                .lDongRegnCd("39").lDongSignguCd("000").addr("제주")
                .lat(33.4).lng(126.5)
                .eventStartDate(LocalDate.of(2026, 9, 1)).eventEndDate(LocalDate.of(2026, 9, 5))
                .build());
        festivalRepository.save(Festival.builder()
                .contentId("wrong-region").title("지역 다름").category("EV01")
                .lDongRegnCd("11").lDongSignguCd("140").addr("서울")
                .lat(37.5).lng(127.1)
                .eventStartDate(LocalDate.of(2026, 8, 1)).eventEndDate(LocalDate.of(2026, 8, 5))
                .build());

        List<Festival> result = festivalRepository.findOverlapping(
                "39", LocalDate.of(2026, 8, 2), LocalDate.of(2026, 8, 4));

        assertThat(result).extracting(Festival::getContentId).containsExactly("overlap-in-range");
    }
}
