package com.ssafy.ieumgil.domain.chatbot.tool;

import com.ssafy.ieumgil.domain.block.entity.Block;

import java.util.List;
import java.util.function.Supplier;

/**
 * 한 메시지 처리 안에서 보드를 한 번만 읽는다.
 *
 * <p>보드를 쓰는 곳이 둘이다 — 보드 조회 tool과, 경로 tool이 장소명을 좌표로 바꿀 때 쓰는
 * 리졸버다. 각자 조회하면 한 메시지에 같은 쿼리가 두 번 나간다(실측으로 확인했다).
 * 요청마다 이 공급자를 하나 만들어 둘에게 넘기면 몇 번 쓰이든 쿼리는 한 번이다.
 *
 * <p><b>지연</b>이라는 점이 핵심이다. 보드를 쓰지 않는 대다수 대화에서는 쿼리가 아예 나가지
 * 않는다 — 보드를 프롬프트에 상시 주입하지 않고 tool로 분리한 이유와 같은 계산이다.
 */
public class RequestScopedBoard implements Supplier<List<Block>> {

    private final Supplier<List<Block>> loader;

    private List<Block> cached;

    public RequestScopedBoard(Supplier<List<Block>> loader) {
        this.loader = loader;
    }

    @Override
    public List<Block> get() {
        if (cached == null) {
            cached = loader.get();
        }
        return cached;
    }
}
