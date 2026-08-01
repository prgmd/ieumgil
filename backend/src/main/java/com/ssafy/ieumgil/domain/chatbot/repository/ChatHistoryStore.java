package com.ssafy.ieumgil.domain.chatbot.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ssafy.ieumgil.domain.chatbot.exception.ChatbotErrorCode;
import com.ssafy.ieumgil.domain.chatbot.exception.ChatbotException;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

@Component
@RequiredArgsConstructor
public class ChatHistoryStore {

    private static final int MAX_TURNS = 6;
    private static final int MAX_ELEMENTS = MAX_TURNS * 2;
    private static final Duration TTL = Duration.ofDays(1);

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public List<ChatTurn> loadHistory(Long projectId, Long memberId) {
        List<String> raw = redisTemplate.opsForList().range(key(projectId, memberId), 0, -1);
        if (raw == null) {
            return List.of();
        }
        return raw.stream().map(this::deserialize).toList();
    }

    public void appendExchange(Long projectId, Long memberId, ChatTurn userTurn, ChatTurn assistantTurn) {
        String key = key(projectId, memberId);
        redisTemplate.opsForList().rightPush(key, serialize(userTurn));
        redisTemplate.opsForList().rightPush(key, serialize(assistantTurn));
        redisTemplate.opsForList().trim(key, -MAX_ELEMENTS, -1);
        redisTemplate.expire(key, TTL);
    }

    private String key(Long projectId, Long memberId) {
        return "chatbot:history:%d:%d".formatted(projectId, memberId);
    }

    private String serialize(ChatTurn turn) {
        try {
            return objectMapper.writeValueAsString(turn);
        } catch (Exception e) {
            throw new ChatbotException(ChatbotErrorCode.HISTORY_STORE_FAILED);
        }
    }

    private ChatTurn deserialize(String json) {
        try {
            return objectMapper.readValue(json, ChatTurn.class);
        } catch (Exception e) {
            throw new ChatbotException(ChatbotErrorCode.HISTORY_STORE_FAILED);
        }
    }
}
