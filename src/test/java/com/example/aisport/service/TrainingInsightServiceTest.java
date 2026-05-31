package com.example.aisport.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class TrainingInsightServiceTest {

    private TrainingInsightService service;

    @BeforeEach
    void setUp() {
        service = new TrainingInsightService();
    }

    @Test
    void shouldCalculateScoreWithValidInput() {
        List<Map<String, Object>> tips = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            Map<String, Object> tip = new LinkedHashMap<>();
            tip.put("min_angle", 80.0);
            tip.put("rep_index", i + 1);
            tips.add(tip);
        }
        Map<String, Object> analysis = new LinkedHashMap<>();
        analysis.put("rep_count", 10);
        analysis.put("tips", tips);

        Map<String, Object> result = service.calculateScore("PUSHUP", analysis);
        assertNotNull(result.get("finalScore"));
        assertEquals(10, result.get("repCount"));
        assertEquals(80.0, (Double) result.get("avgMinAngle"), 0.1);
        assertEquals(80.0, (Double) result.get("targetAngle"), 0.1);
        assertNotNull(result.get("level"));
        assertNotNull(result.get("computedAt"));
    }

    @Test
    void shouldHandleEmptyTips() {
        Map<String, Object> analysis = new LinkedHashMap<>();
        analysis.put("rep_count", 0);
        analysis.put("tips", List.of());

        Map<String, Object> result = service.calculateScore("PUSHUP", analysis);
        assertEquals(0, result.get("repCount"));
        assertNull(result.get("avgMinAngle"));
        assertNotNull(result.get("finalScore"));
    }

    @ParameterizedTest
    @CsvSource({
        "PUSHUP, 80.0",
        "SQUAT, 100.0",
        "BENCH_PRESS, 85.0",
        "DEADLIFT, 120.0",
        "DUMBBELL_SHOULDER_PRESS, 85.0",
        "DUMBBELL_LATERAL_RAISE, 95.0",
        "DUMBBELL_BICEP_CURL, 70.0",
        "PULL_UP, 75.0"
    })
    void shouldReturnCorrectTargetAngle(String exerciseType, double expectedAngle) {
        Map<String, Object> analysis = new LinkedHashMap<>();
        analysis.put("rep_count", 5);
        analysis.put("tips", List.of());

        Map<String, Object> result = service.calculateScore(exerciseType, analysis);
        assertEquals(expectedAngle, (Double) result.get("targetAngle"), 0.01);
    }

    @Test
    void shouldReturnValidLevelString() {
        List<Map<String, Object>> tips = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            Map<String, Object> tip = new LinkedHashMap<>();
            tip.put("min_angle", 75.0);
            tip.put("rep_index", i + 1);
            tips.add(tip);
        }
        Map<String, Object> analysis = new LinkedHashMap<>();
        analysis.put("rep_count", 20);
        analysis.put("tips", tips);

        Map<String, Object> result = service.calculateScore("PUSHUP", analysis);
        String level = (String) result.get("level");
        assertTrue(List.of("优秀", "良好", "一般", "待提升").contains(level));
    }

    @Test
    void shouldUseRepCountFieldFallback() {
        Map<String, Object> analysis = new LinkedHashMap<>();
        analysis.put("repCount", 8);
        analysis.put("tips", List.of());

        Map<String, Object> result = service.calculateScore("PUSHUP", analysis);
        assertEquals(8, result.get("repCount"));
    }

    @Test
    void shouldHandleNullAnalysis() {
        Map<String, Object> result = service.calculateScore("PUSHUP", new HashMap<>());
        assertNotNull(result.get("finalScore"));
        assertEquals(0, result.get("repCount"));
    }
}
