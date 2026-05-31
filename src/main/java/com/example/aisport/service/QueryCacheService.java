package com.example.aisport.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Optional;

@Service
public class QueryCacheService {

    private final StringRedisTemplate redisTemplate;

    @Value("${app.redis.cache.enabled:false}")
    private boolean enabled;

    @Autowired
    public QueryCacheService(@Autowired(required = false) StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public Optional<String> get(String key) {
        if (!enabled || redisTemplate == null || key == null || key.isBlank()) {
            return Optional.empty();
        }
        try {
            String value = redisTemplate.opsForValue().get(key);
            return Optional.ofNullable(value);
        } catch (Exception ignored) {
            return Optional.empty();
        }
    }

    public void put(String key, String value, Duration ttl) {
        if (!enabled || redisTemplate == null || key == null || key.isBlank() || value == null) {
            return;
        }
        try {
            if (ttl != null && !ttl.isNegative() && !ttl.isZero()) {
                redisTemplate.opsForValue().set(key, value, ttl);
            } else {
                redisTemplate.opsForValue().set(key, value);
            }
        } catch (Exception ignored) {
        }
    }

    public void evict(String key) {
        if (!enabled || redisTemplate == null || key == null || key.isBlank()) {
            return;
        }
        try {
            redisTemplate.delete(key);
        } catch (Exception ignored) {
        }
    }
}