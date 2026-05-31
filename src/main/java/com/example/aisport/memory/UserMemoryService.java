package com.example.aisport.memory;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

@Service
public class UserMemoryService {

    private static final Logger log = LoggerFactory.getLogger(UserMemoryService.class);

    private final UserMemoryRepository repository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public UserMemoryService(UserMemoryRepository repository) {
        this.repository = repository;
    }

    public Optional<UserMemory> findByUserId(Long userId) {
        return repository.findByUserId(userId);
    }

    public Optional<UserMemory> findByUsername(String username) {
        return repository.findByUsername(username);
    }

    public Map<String, Object> getProfileMap(Long userId) {
        return repository.findByUserId(userId)
                .map(mem -> {
                    try {
                        if (mem.getProfileJson() != null) {
                            return objectMapper.readValue(mem.getProfileJson(), new TypeReference<Map<String, Object>>() {});
                        }
                    } catch (Exception e) {
                        log.warn("Failed to parse profile JSON for user {}", userId);
                    }
                    Map<String, Object> fallback = new LinkedHashMap<>();
                    fallback.put("totalSessions", mem.getTotalSessions());
                    fallback.put("avgScore", mem.getAvgScore());
                    fallback.put("scoreTrend", mem.getScoreTrend());
                    fallback.put("weakExerciseTypes", mem.getWeakExerciseTypes());
                    fallback.put("commonMistakes", mem.getCommonMistakes());
                    return fallback;
                })
                .orElse(Map.of());
    }

    public void saveProfile(Long userId, String username, Map<String, Object> profile) {
        UserMemory mem = repository.findByUserId(userId).orElseGet(() -> {
            UserMemory m = new UserMemory();
            m.setUserId(userId);
            m.setUsername(username);
            return m;
        });

        try {
            mem.setProfileJson(objectMapper.writeValueAsString(profile));
        } catch (Exception e) {
            log.warn("Failed to serialize profile JSON", e);
        }

        mem.setTotalSessions(toInt(profile.get("totalSessions")));
        mem.setAvgScore(toDouble(profile.get("avgScore")));
        mem.setScoreTrend(String.valueOf(profile.getOrDefault("scoreTrend", "")));
        mem.setWeakExerciseTypes(String.valueOf(profile.getOrDefault("weakExerciseTypes", "")));
        mem.setCommonMistakes(String.valueOf(profile.getOrDefault("commonMistakes", "")));
        mem.setUpdatedAt(LocalDateTime.now());

        repository.save(mem);
        log.info("Saved memory for user {}: totalSessions={}, avgScore={}", username, mem.getTotalSessions(), mem.getAvgScore());
    }

    private int toInt(Object val) {
        if (val instanceof Number n) return n.intValue();
        return 0;
    }

    private Double toDouble(Object val) {
        if (val instanceof Number n) return n.doubleValue();
        return null;
    }
}
