package com.ssafy.ieumgil.domain.chatbot.tool;

import com.ssafy.ieumgil.domain.block.entity.Block;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 프로젝트 보드를 챗봇이 읽을 수 있는 형태로 접는다 (BOT-03 컨텍스트).
 *
 * <p>좌표를 함께 담는 것이 설계의 핵심이다. 좌표가 없으면 "2번째 블록에서 3번째 블록까지
 * 택시비" 같은 질문에서 도보·택시 tool이 블록 이름으로 카카오를 재검색해야 해 홉이 하나
 * 늘어난다. haiku의 멀티홉 신뢰도가 걱정거리로 기록돼 있으므로 홉을 줄이는 쪽을 택했다.
 *
 * <p>담지 않는 것: {@code detail}(최대 500자로 토큰만 먹고 대화에 거의 쓰이지 않는다),
 * {@code address}·{@code placeId}(좌표가 있으면 불필요), 사람 정보(마지막 수정자는 컬럼
 * 자체가 없고 작성자는 챗봇에 물을 값어치가 낮다).
 */
public record BoardSummary(
        List<DayPlan> days,
        List<BoardBlock> pool
) {

    public record DayPlan(int dayNo, List<BoardBlock> blocks) {
    }

    /** {@code order}는 그 Day 안에서 1부터. "2번째 블록"이라는 사용자 지시가 성립하려면 필요하다. */
    public record BoardBlock(
            int order,
            Long blockId,
            String name,
            String category,
            String startTime,
            int durationMin,
            int budget,
            Double lat,
            Double lng
    ) {
    }

    /**
     * @param blocks {@code BlockRepository.findChain} 결과 — (orderKey, id) 순으로 정렬돼 있고
     *               dayNo가 null인 후보(POOL) 블록도 함께 들어 있다.
     */
    public static BoardSummary from(List<Block> blocks) {
        // Day 번호 순으로 내보낸다. 입력은 orderKey 기준 정렬이라 Day가 섞여 들어올 수 있다.
        Map<Integer, List<Block>> byDay = new TreeMap<>();
        List<Block> poolBlocks = new ArrayList<>();

        for (Block block : blocks) {
            if (block.getDayNo() == null) {
                poolBlocks.add(block);
            } else {
                byDay.computeIfAbsent(block.getDayNo(), key -> new ArrayList<>()).add(block);
            }
        }

        List<DayPlan> days = new ArrayList<>();
        byDay.forEach((dayNo, dayBlocks) -> days.add(new DayPlan(dayNo, toBoardBlocks(dayBlocks))));

        return new BoardSummary(List.copyOf(days), toBoardBlocks(poolBlocks));
    }

    private static List<BoardBlock> toBoardBlocks(List<Block> blocks) {
        List<BoardBlock> result = new ArrayList<>(blocks.size());
        for (int i = 0; i < blocks.size(); i++) {
            result.add(toBoardBlock(i + 1, blocks.get(i)));
        }
        return List.copyOf(result);
    }

    private static BoardBlock toBoardBlock(int order, Block block) {
        return new BoardBlock(
                order,
                block.getId(),
                block.getName(),
                block.getCategory().name(),
                // 시각 없는(느슨한) 블록은 null로 남긴다 — 서버가 시각을 지어내지 않는다
                block.getStartTime() == null ? null : block.getStartTime().toString(),
                block.getDurationMin(),
                block.getBudget(),
                toDouble(block.getLat()),
                toDouble(block.getLng())
        );
    }

    private static Double toDouble(BigDecimal value) {
        return value == null ? null : value.doubleValue();
    }
}
