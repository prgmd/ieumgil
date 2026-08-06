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
 *
 * <p>{@code available}은 "지금 보드를 못 읽었다"를 "보드가 비어 있다"와 구분하려고 둔다.
 * 둘이 같은 모양이면 DB가 잠깐 흔들린 순간 모델이 "아직 아무것도 안 넣으셨네요"라고 단언하고,
 * 사용자에겐 일정이 사라진 것으로 보인다. route tool의 {@code found}와 같은 취지다.
 */
public record BoardSummary(
        boolean available,
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

    /** 보드를 읽지 못했을 때 — 빈 보드와 같은 모양이 되지 않도록 available=false로 내려간다. */
    public static BoardSummary unavailable() {
        return new BoardSummary(false, List.of(), List.of());
    }

    /**
     * @param blocks {@code BlockRepository.findChain} 결과 — 시작 오프셋이 null인 후보(POOL)
     *               블록도 함께 들어 있다. Day 번호와 시각은 저장된 값이 아니라 그 오프셋에서
     *               파생하므로, 자정을 넘긴 블록은 다음 Day로 접힌다.
     */
    public static BoardSummary from(List<Block> blocks) {
        // Day 번호 순으로 내보낸다. 입력은 오프셋 기준 정렬이라 Day가 섞여 들어올 수 있다.
        Map<Integer, List<Block>> byDay = new TreeMap<>();
        List<Block> poolBlocks = new ArrayList<>();

        for (Block block : blocks) {
            if (block.isInPool()) {
                poolBlocks.add(block);
            } else {
                byDay.computeIfAbsent(block.dayNo(), key -> new ArrayList<>()).add(block);
            }
        }

        // 키 언박싱이 안전한 이유: POOL을 위에서 걸렀으므로 dayNo()가 null인 블록은 여기 없다.
        List<DayPlan> days = new ArrayList<>();
        byDay.forEach((dayNo, dayBlocks) -> days.add(new DayPlan(dayNo, toBoardBlocks(dayBlocks))));

        return new BoardSummary(true, List.copyOf(days), toBoardBlocks(poolBlocks));
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
                // 시각이 없는(=후보) 블록은 null로 남긴다 — 서버가 시각을 지어내지 않는다
                toHhmm(block.startMinuteOfDay()),
                block.getDurationMin(),
                block.getBudget(),
                toDouble(block.getLat()),
                toDouble(block.getLng())
        );
    }

    /**
     * Day 안의 분을 {@code "HH:mm"}으로 — 옛 {@code LocalTime.toString()}과 같은 문자열이다.
     * 이 표기는 프롬프트 계약이라 자릿수·구분자가 달라지면 모델 동작이 조용히 바뀐다.
     */
    private static String toHhmm(Integer minuteOfDay) {
        return minuteOfDay == null
                ? null
                : String.format("%02d:%02d", minuteOfDay / 60, minuteOfDay % 60);
    }

    private static Double toDouble(BigDecimal value) {
        return value == null ? null : value.doubleValue();
    }
}
