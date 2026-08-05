package com.ssafy.ieumgil.domain.transit.client;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;

/**
 * ODsay 응답 캐시. 값은 JSON 문자열로 넣고 호출자가 자기 타입으로 꺼낸다.
 *
 * <p><b>모든 실패를 강등한다.</b> 캐시는 부가 장치다 — Redis가 흔들리는 동안 교통 후보 전체가
 * 500이 되면 캐시가 주는 가치보다 대가가 크다. 읽기 실패는 미스로, 쓰기 실패는 무시로 떨어뜨리고
 * 그대로 외부 API를 부른다({@code ChatHistoryStore}가 대화 이력에 쓰는 것과 같은 규칙).
 *
 * <p>깨진 값도 미스로 본다. 응답 DTO가 바뀌면 예전 형태가 역직렬화되지 않는데, 그것 때문에
 * TTL이 다 지날 때까지 그 구간이 실패하면 안 된다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TransitApiCache {

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    /**
     * 캐시를 쓰지 않는 인스턴스. 외부 API 계약만 확인하는 live 테스트가 Redis 없이
     * {@link OdsayClient}를 만들 때 쓴다 — 그 테스트들이 검증하는 것은 ODsay 응답 파싱이고
     * 캐시는 그 검증에 끼어들면 안 된다(히트가 나면 실호출을 하지 않는다).
     */
    public static TransitApiCache disabled() {
        return new TransitApiCache(null, null);
    }

    private boolean isDisabled() {
        return redisTemplate == null || objectMapper == null;
    }

    public <T> Optional<T> read(String key, TypeReference<T> type) {
        if (isDisabled()) {
            return Optional.empty();
        }
        String raw;
        try {
            raw = redisTemplate.opsForValue().get(key);
        } catch (RuntimeException e) {
            log.warn("교통 캐시 조회 실패 — 미스로 진행한다 key={}", key, e);
            return Optional.empty();
        }
        if (raw == null) {
            return Optional.empty();
        }
        try {
            return Optional.ofNullable(objectMapper.readValue(raw, type));
        } catch (RuntimeException | java.io.IOException e) {
            log.warn("교통 캐시 값이 깨졌다 — 미스로 진행한다 key={}", key, e);
            return Optional.empty();
        }
    }

    public void write(String key, Object value, Duration ttl) {
        if (isDisabled()) {
            return;
        }
        try {
            redisTemplate.opsForValue().set(key, objectMapper.writeValueAsString(value), ttl);
        } catch (RuntimeException | java.io.IOException e) {
            log.warn("교통 캐시 저장 실패 — 무시한다 key={}", key, e);
        }
    }
}
