package com.ssafy.ieumgil.domain.auth.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class RefreshTokenRepository {

    private static final String KEY_PREFIX = "refresh:";

    private final StringRedisTemplate redisTemplate;

    public void save(Long userId, String refreshToken, long ttlMillis) {
        redisTemplate.opsForValue().set(KEY_PREFIX + userId, refreshToken, Duration.ofMillis(ttlMillis));
    }

    public Optional<String> findByUserId(Long userId) {
        return Optional.ofNullable(redisTemplate.opsForValue().get(KEY_PREFIX + userId));
    }

    public void deleteByUserId(Long userId) {
        redisTemplate.delete(KEY_PREFIX + userId);
    }
}
