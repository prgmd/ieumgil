package com.ssafy.ieumgil.domain.chatbot.tool;

import com.ssafy.ieumgil.domain.block.entity.Block;
import com.ssafy.ieumgil.domain.block.entity.BlockCategory;
import com.ssafy.ieumgil.domain.block.entity.BlockSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 프로젝트 보드를 챗봇이 읽을 수 있는 형태로 접는다 (BOT-03 컨텍스트).
 *
 * <p>좌표를 함께 담는 것이 핵심이다. 좌표가 없으면 "2번째 블록에서 3번째 블록까지 택시비"
 * 같은 질문에서 도보·택시 tool이 블록 이름으로 카카오를 재검색해야 해 홉이 하나 늘고,
 * haiku의 멀티홉 신뢰도가 그만큼 나빠진다.
 */
class BoardSummaryTest {

    private static Block block(Integer dayNo, String orderKey, String name,
                              BlockCategory category, LocalTime startTime, int durationMin,
                              int budget, Double lat, Double lng) {
        return Block.builder()
                .dayNo(dayNo)
                .orderKey(orderKey)
                .name(name)
                .category(category)
                .startTime(startTime)
                .durationMin(durationMin)
                .budget(budget)
                .lat(lat == null ? null : java.math.BigDecimal.valueOf(lat))
                .lng(lng == null ? null : java.math.BigDecimal.valueOf(lng))
                .source(BlockSource.KAKAO)
                .build();
    }

    @Test
    @DisplayName("Day별로 묶고 그 안에서 1부터 순번을 붙인다 — \"2번째 블록\" 지시가 성립해야 한다")
    void groupsByDayAndNumbersFromOne() {
        List<Block> blocks = List.of(
                block(1, "a0", "성산일출봉", BlockCategory.SPOT, LocalTime.of(9, 0), 90, 5000, 33.45, 126.94),
                block(1, "a1", "우도", BlockCategory.SPOT, LocalTime.of(11, 0), 120, 10000, 33.50, 126.95),
                block(2, "a0", "카페", BlockCategory.FOOD, null, 60, 8000, 33.48, 126.50)
        );

        BoardSummary summary = BoardSummary.from(blocks);

        assertThat(summary.days()).hasSize(2);
        assertThat(summary.days().get(0).dayNo()).isEqualTo(1);
        assertThat(summary.days().get(0).blocks())
                .extracting(BoardSummary.BoardBlock::order)
                .containsExactly(1, 2);
        assertThat(summary.days().get(0).blocks())
                .extracting(BoardSummary.BoardBlock::name)
                .containsExactly("성산일출봉", "우도");
        assertThat(summary.days().get(1).dayNo()).isEqualTo(2);
        assertThat(summary.days().get(1).blocks()).hasSize(1);
    }

    @Test
    @DisplayName("dayNo가 null인 블록은 후보(POOL)로 분리한다")
    void separatesPoolBlocks() {
        List<Block> blocks = List.of(
                block(1, "a0", "체인 블록", BlockCategory.SPOT, LocalTime.of(9, 0), 90, 0, 33.45, 126.94),
                block(null, "a1", "후보 블록", BlockCategory.FOOD, null, 60, 0, 33.48, 126.50)
        );

        BoardSummary summary = BoardSummary.from(blocks);

        assertThat(summary.days()).hasSize(1);
        assertThat(summary.pool()).extracting(BoardSummary.BoardBlock::name)
                .containsExactly("후보 블록");
    }

    @Test
    @DisplayName("좌표를 함께 담는다 — 도보·택시 tool이 재검색 없이 바로 쓴다")
    void carriesCoordinates() {
        List<Block> blocks = List.of(
                block(1, "a0", "성산일출봉", BlockCategory.SPOT, null, 90, 0, 33.4581, 126.9425));

        BoardSummary.BoardBlock first = BoardSummary.from(blocks).days().get(0).blocks().get(0);

        assertThat(first.lat()).isEqualTo(33.4581);
        assertThat(first.lng()).isEqualTo(126.9425);
    }

    @Test
    @DisplayName("시각이 없는 블록은 null로 남긴다 — 서버가 시각을 지어내지 않는다")
    void keepsMissingStartTimeAsNull() {
        List<Block> blocks = List.of(
                block(1, "a0", "시각 미정", BlockCategory.SPOT, null, 60, 0, 33.45, 126.94));

        assertThat(BoardSummary.from(blocks).days().get(0).blocks().get(0).startTime()).isNull();
    }

    @Test
    @DisplayName("예산을 담는다 — \"얼마나 썼어\"에 답할 수 있어야 한다")
    void carriesBudget() {
        List<Block> blocks = List.of(
                block(1, "a0", "입장료 있는 곳", BlockCategory.SPOT, null, 60, 12000, 33.45, 126.94));

        assertThat(BoardSummary.from(blocks).days().get(0).blocks().get(0).budget()).isEqualTo(12000);
    }

    @Test
    @DisplayName("Day 번호 순으로 정렬한다 — 블록 정렬은 orderKey 기준이라 Day가 섞여 들어올 수 있다")
    void sortsDaysByDayNumber() {
        List<Block> blocks = List.of(
                block(3, "a0", "3일차", BlockCategory.SPOT, null, 60, 0, 33.45, 126.94),
                block(1, "a1", "1일차", BlockCategory.SPOT, null, 60, 0, 33.45, 126.94),
                block(2, "a2", "2일차", BlockCategory.SPOT, null, 60, 0, 33.45, 126.94)
        );

        assertThat(BoardSummary.from(blocks).days())
                .extracting(BoardSummary.DayPlan::dayNo)
                .containsExactly(1, 2, 3);
    }

    @Test
    @DisplayName("빈 보드는 빈 목록 둘이다 — 아직 아무것도 안 넣었다는 사실을 모델이 알아야 한다")
    void emptyBoardYieldsEmptyLists() {
        BoardSummary summary = BoardSummary.from(List.of());

        assertThat(summary.days()).isEmpty();
        assertThat(summary.pool()).isEmpty();
    }
}
