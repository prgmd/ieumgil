package com.ssafy.ieumgil.domain.chatbot.tool;

import com.ssafy.ieumgil.domain.block.entity.Block;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;

import java.util.List;
import java.util.function.Supplier;

/**
 * 현재 프로젝트 보드(확정 일정 + 후보 목록)를 조회하는 tool (BOT-03 컨텍스트).
 *
 * <p>매 메시지에 보드를 주입하지 않고 tool로 둔 이유는 예산이다 — 대다수 질문은 보드를
 * 보지 않으므로 상시 주입하면 안 쓰는 호출에도 토큰이 나간다. 반대로 목적지·기간·인원처럼
 * 작고 거의 항상 관련된 값은 시스템 프롬프트에 상시 주입한다.
 *
 * <p>파라미터가 없는 tool이라 호출 여부가 순수하게 description 매칭에 달려 있다. 축제 tool도
 * 같은 형태이고 한때 트리거가 불안정했던 전례가 있어, 이 tool은 실모델로 트리거를 확인해야 한다.
 */
@Slf4j
public class BoardTool {

    private final Supplier<List<Block>> boardSupplier;

    public BoardTool(Supplier<List<Block>> boardSupplier) {
        this.boardSupplier = boardSupplier;
    }

    @Tool(description = """
            Call this whenever the user's question depends on what is already in their itinerary — for example "how is my plan so far", "what did I put on day 2", "is day 1 too packed", "what's in my candidate list", "how much have I budgeted", or any request that refers to a block by position such as "the 2nd stop".
            It takes no input: it automatically reads the current project's board. So call it directly and immediately — never ask the user to tell you their itinerary.
            Returns the confirmed itinerary grouped by day (each block numbered from 1 within its day) plus the candidate pool that is not placed on any day yet. Each block carries its coordinates, so when the user asks about travel between blocks, take the coordinates from here and pass them to the walking or taxi route tool instead of searching for the place by name again.
            An empty result means the user has not added anything yet — say so rather than guessing what might be in the plan.
            """)
    public BoardSummary getCurrentPlan() {
        try {
            return BoardSummary.from(boardSupplier.get());
        } catch (RuntimeException e) {
            log.warn("board tool call failed", e);
            return new BoardSummary(List.of(), List.of());
        }
    }
}
