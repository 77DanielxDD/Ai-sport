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

    // ── buildRepEvaluations tests ──────────────────────────────────────────

    @Test
    void shouldBuildRepEvaluationsFromNewFields() {
        List<Map<String, Object>> repEvents = new ArrayList<>();
        Map<String, Object> ev = new LinkedHashMap<>();
        ev.put("rep_index", 1);
        ev.put("min_angle", 78.0);
        ev.put("depth_level", "good");
        ev.put("depth_score", 88.0);
        ev.put("tempo_ms", 1200);
        ev.put("tempo_level", "normal");
        ev.put("stability_score", 82.0);
        ev.put("symmetry_diff_deg", 3.5);
        ev.put("symmetry_level", "good");
        ev.put("tip", "Depth is good");
        ev.put("evidence", List.of("最低角度 78.0°", "与目标角度相差 2.0°", "左右角度差 3.5°"));
        repEvents.add(ev);

        Map<String, Object> analysis = new LinkedHashMap<>();
        analysis.put("rep_events", repEvents);

        List<Map<String, Object>> result = service.buildRepEvaluations("PUSHUP", analysis);
        assertEquals(1, result.size());
        Map<String, Object> r = result.get(0);
        assertEquals(1, r.get("repIndex"));
        assertEquals(88.0, (Double) r.get("depthScore"), 0.1);
        assertEquals("good", r.get("depthLevel"));
        assertEquals(1200, r.get("tempoMs"));
        assertEquals("normal", r.get("tempoLevel"));
        assertEquals(82.0, (Double) r.get("stabilityScore"), 0.1);
        assertEquals(3.5, (Double) r.get("symmetryDiffDeg"), 0.1);
        assertNotNull(r.get("score"));
        assertNotNull(r.get("level"));
        assertNotNull(r.get("diagnosis"));
        assertNotNull(r.get("suggestion"));
        assertTrue(r.get("evidence") instanceof List);
    }

    @Test
    void shouldFallbackToOldTipsWhenNoRepEvents() {
        List<Map<String, Object>> tips = new ArrayList<>();
        Map<String, Object> tip = new LinkedHashMap<>();
        tip.put("rep_index", 1);
        tip.put("min_angle", 82.0);
        tip.put("tip", "Depth is good. Keep elbow and wrist stable.");
        tips.add(tip);

        Map<String, Object> analysis = new LinkedHashMap<>();
        analysis.put("tips", tips);

        List<Map<String, Object>> result = service.buildRepEvaluations("PUSHUP", analysis);
        assertEquals(1, result.size());
        Map<String, Object> r = result.get(0);
        assertEquals(1, r.get("repIndex"));
        assertNotNull(r.get("depthScore"));
        assertNotNull(r.get("depthLevel"));
        assertEquals(-1, r.get("tempoMs"));
        assertEquals("unknown", r.get("tempoLevel"));
        assertNotNull(r.get("diagnosis"));
        assertNotNull(r.get("suggestion"));
    }

    @Test
    void shouldHandleEmptyRepEvents() {
        Map<String, Object> analysis = new LinkedHashMap<>();
        analysis.put("rep_events", List.of());

        List<Map<String, Object>> result = service.buildRepEvaluations("SQUAT", analysis);
        assertTrue(result.isEmpty());
    }

    @Test
    void shouldHandleNullAnalysisForRepEvals() {
        List<Map<String, Object>> result = service.buildRepEvaluations("PUSHUP", new HashMap<>());
        assertTrue(result.isEmpty());
    }

    @Test
    void shouldKeepScoresInRange() {
        List<Map<String, Object>> repEvents = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            Map<String, Object> ev = new LinkedHashMap<>();
            ev.put("rep_index", i + 1);
            ev.put("min_angle", i < 3 ? 100.0 : i < 7 ? 85.0 : 72.0);
            ev.put("depth_level", i < 3 ? "bad" : i < 7 ? "warning" : "good");
            ev.put("depth_score", i < 3 ? 30.0 : i < 7 ? 60.0 : 90.0);
            ev.put("tempo_ms", 800 + i * 200);
            ev.put("tempo_level", "normal");
            ev.put("stability_score", 50.0 + i * 5.0);
            ev.put("symmetry_diff_deg", 10.0 - i);
            ev.put("symmetry_level", i > 5 ? "good" : "warning");
            ev.put("tip", "tip " + (i + 1));
            ev.put("evidence", List.of("evidence " + (i + 1)));
            repEvents.add(ev);
        }
        Map<String, Object> analysis = new LinkedHashMap<>();
        analysis.put("rep_events", repEvents);

        List<Map<String, Object>> result = service.buildRepEvaluations("PUSHUP", analysis);
        assertEquals(10, result.size());
        for (Map<String, Object> r : result) {
            Double score = r.get("score") instanceof Number n ? n.doubleValue() : null;
            assertNotNull(score);
            assertTrue(score >= 0.0, "score should be >= 0: " + score);
            assertTrue(score <= 100.0, "score should be <= 100: " + score);
            String level = (String) r.get("level");
            assertTrue(List.of("优秀", "良好", "一般", "待提升").contains(level),
                "unexpected level: " + level);
        }
    }

    @Test
    void shouldHandleMixedOldAndNewEvents() {
        // Old-style events without depth_level, tempo_level etc.
        List<Map<String, Object>> oldEvents = new ArrayList<>();
        Map<String, Object> oldEv = new LinkedHashMap<>();
        oldEv.put("rep_index", 1);
        oldEv.put("min_angle", 78.0);
        oldEv.put("tip", "Old format tip");
        oldEvents.add(oldEv);

        Map<String, Object> analysis = new LinkedHashMap<>();
        analysis.put("rep_events", oldEvents);

        List<Map<String, Object>> result = service.buildRepEvaluations("PUSHUP", analysis);
        assertEquals(1, result.size());
        Map<String, Object> r = result.get(0);
        assertEquals(-1, r.get("tempoMs"));
        assertEquals("unknown", r.get("tempoLevel"));
        assertNotNull(r.get("score"));
    }

    @Test
    void shouldCombineWithCalculateScore() {
        List<Map<String, Object>> repEvents = new ArrayList<>();
        Map<String, Object> ev = new LinkedHashMap<>();
        ev.put("rep_index", 1);
        ev.put("min_angle", 78.0);
        ev.put("depth_level", "good");
        ev.put("depth_score", 88.0);
        ev.put("tempo_ms", 1200);
        ev.put("tempo_level", "normal");
        ev.put("stability_score", 82.0);
        ev.put("symmetry_diff_deg", 3.5);
        ev.put("symmetry_level", "good");
        ev.put("tip", "Depth is good");
        repEvents.add(ev);

        Map<String, Object> analysis = new LinkedHashMap<>();
        analysis.put("rep_count", 1);
        analysis.put("rep_events", repEvents);
        analysis.put("tips", List.of(Map.of("rep_index", 1, "min_angle", 78.0, "tip", "Depth is good")));

        // old calculateScore should still work
        Map<String, Object> score = service.calculateScore("PUSHUP", analysis);
        assertNotNull(score.get("finalScore"));

        // new buildRepEvaluations should work
        List<Map<String, Object>> evals = service.buildRepEvaluations("PUSHUP", analysis);
        assertFalse(evals.isEmpty());
    }
}
