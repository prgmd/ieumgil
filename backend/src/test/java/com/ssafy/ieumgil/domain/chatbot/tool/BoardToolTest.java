package com.ssafy.ieumgil.domain.chatbot.tool;

import com.ssafy.ieumgil.domain.block.entity.Block;
import com.ssafy.ieumgil.domain.block.entity.BlockCategory;
import com.ssafy.ieumgil.domain.block.entity.BlockSource;
import com.ssafy.ieumgil.domain.block.repository.BlockRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * 현재 프로젝트 보드를 조회하는 tool (BOT-03 컨텍스트).
 *
 * <p>매 메시지에 보드를 주입하지 않고 tool로 둔 이유는 예산이다 — 대다수 질문은 보드를
 * 보지 않으므로 상시 주입하면 안 쓰는 호출에도 토큰이 나간다. 반대로 목적지·기간·인원처럼
 * 작고 거의 항상 관련된 값은 프롬프트에 상시 주입한다.
 */
@ExtendWith(MockitoExtension.class)
class BoardToolTest {

    @Mock
    private BlockRepository blockRepository;

    private static Block block(Integer dayNo, String name) {
        return Block.builder()
                .dayNo(dayNo)
                .orderKey("a0")
                .name(name)
                .category(BlockCategory.SPOT)
                .durationMin(60)
                .budget(0)
                .lat(BigDecimal.valueOf(33.45))
                .lng(BigDecimal.valueOf(126.94))
                .source(BlockSource.KAKAO)
                .build();
    }

    @Test
    @DisplayName("현재 프로젝트의 보드를 조회해 Day별·후보로 접어 반환한다")
    void returnsBoardForCurrentProject() {
        when(blockRepository.findChain(12L)).thenReturn(List.of(
                block(1, "성산일출봉"), block(null, "후보 카페")));
        BoardTool tool = new BoardTool(new RequestScopedBoard(() -> blockRepository.findChain(12L)));

        BoardSummary summary = tool.getCurrentPlan();

        assertThat(summary.days()).hasSize(1);
        assertThat(summary.days().get(0).blocks().get(0).name()).isEqualTo("성산일출봉");
        assertThat(summary.pool()).extracting(BoardSummary.BoardBlock::name).containsExactly("후보 카페");
    }

    @Test
    @DisplayName("보드가 비어 있어도 예외 없이 빈 결과를 준다 — 모델이 '아직 아무것도 없다'를 알아야 한다")
    void emptyBoardIsNotAnError() {
        when(blockRepository.findChain(12L)).thenReturn(List.of());
        BoardTool tool = new BoardTool(new RequestScopedBoard(() -> blockRepository.findChain(12L)));

        BoardSummary summary = tool.getCurrentPlan();

        assertThat(summary.days()).isEmpty();
        assertThat(summary.pool()).isEmpty();
    }

    @Test
    @DisplayName("보드 설명은 route tool에 이름을 넘기라고 해야 한다 — 좌표를 넘기라고 하면 실패한다")
    void descriptionHandsOffPlaceNamesNotCoordinates() throws NoSuchMethodException {
        // route tool의 시그니처는 이름 두 개다. 설명이 좌표를 넘기라고 지시하면 모델이
        // "33.45,126.94"를 장소명으로 카카오에 검색해 쓰레기 결과가 나온다.
        assertThat(TaxiRouteTool.class.getMethod("getTaxiRoute", String.class, String.class)).isNotNull();
        assertThat(WalkingRouteTool.class.getMethod("getWalkingRoute", String.class, String.class)).isNotNull();

        String description = BoardTool.class.getMethod("getCurrentPlan")
                .getAnnotation(org.springframework.ai.tool.annotation.Tool.class).description();

        assertThat(description).doesNotContain("coordinates");
        assertThat(description).contains("name");
    }

    @Test
    @DisplayName("조회가 실패해도 대화는 계속된다 — 빈 결과로 떨어뜨린다")
    void repositoryFailureDegradesToEmptyBoard() {
        when(blockRepository.findChain(12L)).thenThrow(new RuntimeException("db down"));
        BoardTool tool = new BoardTool(new RequestScopedBoard(() -> blockRepository.findChain(12L)));

        BoardSummary summary = tool.getCurrentPlan();

        assertThat(summary.days()).isEmpty();
        assertThat(summary.pool()).isEmpty();
    }
}
