package com.ssafy.ieumgil.domain.chatbot.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ssafy.ieumgil.domain.chatbot.exception.ChatbotErrorCode;
import com.ssafy.ieumgil.domain.chatbot.exception.ChatbotException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * 프로젝트+멤버 단위 최근 대화 이력.
 *
 * <p>이력은 부가 기능이다 — 없으면 대화의 맥락이 얕아질 뿐 답변 자체는 나간다. 그래서 이 저장소의
 * 실패는 예외로 올리지 않고 전부 강등한다. 예외를 올리면 Redis가 흔들리는 동안 챗봇 전체가
 * 500이 되는데, 그건 이력이 주는 가치에 비해 너무 비싼 대가다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChatHistoryStore {

    /** 화면 복원용 저장 상한. LLM 프롬프트는 이 중 마지막 6턴만 쓴다(ChatbotCommandServiceImpl). */
    private static final int MAX_TURNS = 10;
    private static final int MAX_ELEMENTS = MAX_TURNS * 2;
    private static final Duration TTL = Duration.ofDays(1);

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    /** 조회 실패는 빈 이력으로 강등한다. 깨진 항목은 건너뛴다 — 한 건 때문에 TTL 1일 내내 500이 되면 안 된다. */
    public List<ChatTurn> loadHistory(Long projectId, Long memberId) {
        List<String> raw;
        try {
            raw = redisTemplate.opsForList().range(key(projectId, memberId), 0, -1);
        } catch (RuntimeException e) {
            log.warn("대화 이력 조회 실패 — 빈 이력으로 진행한다 projectId={} memberId={}", projectId, memberId, e);
            return List.of();
        }
        if (raw == null) {
            return List.of();
        }
        List<ChatTurn> turns = raw.stream()
                .map(this::deserializeOrNull)
                .toList();
        return alignPairs(turns);
    }

    /**
     * 로드 결과가 (user, assistant) 짝만 남도록 정렬한다.
     *
     * <p>저장은 짝을 원자적으로 붙이지만, 개별 항목 역직렬화 실패가 하나라도 있으면 평평한
     * 리스트의 짝 경계가 어긋난다 — 소비처가 tail을 슬라이스해 role 그대로 GMS 메시지로 넘기므로,
     * 어긋난 이력은 홀수/동일 role 연속이 되어 Anthropic이 alternation 위반(400)을 낸다. 인접한
     * user→assistant만 짝으로 취해 선두 user·엄격한 교대·짝수 길이를 보장한다. 짝을 못 이룬
     * 항목(선두 assistant·꼬리 user)은 버린다. 손상 없는 정상 이력은 그대로 통과한다.
     *
     * <p>여기서 "인접"은 <b>원본 저장 인덱스 기준 인접</b>이다 — 역직렬화 실패 항목({@code null})을
     * 미리 걸러내지 않고 자리에 둔 채 스캔한다. 실패 항목을 먼저 제거하면 남은 항목들이 앞으로
     * 당겨져 리스트-인덱스상 붙어 보이지만 원본에서는 떨어져 있던 서로 다른 턴의 질문/답을 role만
     * 맞다고 짝으로 접착할 수 있다(예: {@code U0,A0,U1,A1}에서 A0·U1이 연속으로 깨지면 U0와 A1이
     * 붙어 보인다). 그래서 {@code turns.get(i)}와 {@code turns.get(i+1)}이 <b>둘 다 non-null이면서</b>
     * role까지 맞을 때만 짝으로 인정한다 — non-null이면 그 자리가 원본에서 실제로 인접했다는 뜻이다.
     */
    private List<ChatTurn> alignPairs(List<ChatTurn> turns) {
        List<ChatTurn> aligned = new ArrayList<>(turns.size());
        int i = 0;
        while (i < turns.size()) {
            ChatTurn turn = turns.get(i);
            ChatTurn next = i + 1 < turns.size() ? turns.get(i + 1) : null;
            boolean userThenAssistant = turn != null
                    && next != null
                    && ChatTurn.ROLE_USER.equals(turn.role())
                    && ChatTurn.ROLE_ASSISTANT.equals(next.role());
            if (userThenAssistant) {
                aligned.add(turn);
                aligned.add(next);
                i += 2;
            } else {
                i++;
            }
        }
        return aligned;
    }

    /**
     * 문답 한 쌍을 원자적으로 덧붙인다.
     *
     * <p>MULTI/EXEC(={@link SessionCallback})으로 묶는 이유: 예전에는 rightPush 2회·trim·expire가
     * 각각 별개 호출이라, 같은 사용자가 두 탭이나 더블클릭으로 동시에 보내면
     * {@code user1, user2, assistant1, assistant2}처럼 끼어들어 문답 짝이 어긋났다. 그 이력이
     * 다음 요청의 GMS 메시지 배열이 되므로 대화가 통째로 뒤틀린다.
     *
     * <p>한계 두 가지. (1) 원자성은 "네 명령이 통째로 실행된다"까지이고 요청 간 순서를 정해주지는
     * 않는다 — 동시 요청 중 어느 쌍이 먼저 들어갈지는 여전히 비결정적이다(짝이 붙어 있기만 하면 된다).
     * (2) 클러스터에서는 한 트랜잭션의 키가 모두 같은 슬롯이어야 하는데, 여기서는 키가 하나뿐이라
     * 제약에 걸리지 않는다. 키를 늘리게 되면 이 가정이 깨진다.
     */
    public void appendExchange(Long projectId, Long memberId, ChatTurn userTurn, ChatTurn assistantTurn) {
        String key = key(projectId, memberId);
        String user = serialize(userTurn);
        String assistant = serialize(assistantTurn);
        try {
            redisTemplate.execute(new SessionCallback<Object>() {
                @Override
                @SuppressWarnings({"unchecked", "rawtypes"})
                public Object execute(RedisOperations operations) {
                    operations.multi();
                    operations.opsForList().rightPushAll(key, user, assistant);
                    operations.opsForList().trim(key, -MAX_ELEMENTS, -1);
                    operations.expire(key, TTL);
                    return operations.exec();
                }
            });
        } catch (RuntimeException e) {
            // 답변은 이미 만들어져 GMS 비용도 나갔다 — 여기서 500을 내면 대가만 치르고 응답을 버리는 셈이다
            log.warn("대화 이력 저장 실패 — 이번 턴은 기록되지 않는다 projectId={} memberId={}", projectId, memberId, e);
        }
    }

    private String key(Long projectId, Long memberId) {
        return "chatbot:history:%d:%d".formatted(projectId, memberId);
    }

    /**
     * 직렬화 실패만 예외로 남긴다. 인프라 장애가 아니라 {@link ChatTurn} 자체가 깨진 프로그래밍
     * 오류라 조용히 삼키면 원인을 못 찾는다(문자열 두 개짜리 record라 실제로는 나기 어렵다).
     */
    private String serialize(ChatTurn turn) {
        try {
            return objectMapper.writeValueAsString(turn);
        } catch (Exception e) {
            throw new ChatbotException(ChatbotErrorCode.HISTORY_STORE_FAILED);
        }
    }

    /** 역직렬화 실패는 그 항목만 버린다(자가 치유). 남은 정상 항목으로 대화는 이어진다. */
    private ChatTurn deserializeOrNull(String json) {
        try {
            return objectMapper.readValue(json, ChatTurn.class);
        } catch (Exception e) {
            log.warn("대화 이력 항목 역직렬화 실패 — 해당 항목을 건너뛴다: {}", json, e);
            return null;
        }
    }
}
