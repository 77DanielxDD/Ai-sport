package com.example.aisport.service;

import com.example.aisport.entity.ExerciseVideo;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class TrainingInsightService {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> buildRepEvaluations(String exerciseType, Map<String, Object> analysis) {
        List<Map<String, Object>> out = new ArrayList<>();
        Object raw = analysis.get("rep_events");
        if (!(raw instanceof List<?> repEventsList)) {
            raw = analysis.get("tips");
        }
        if (!(raw instanceof List<?> sourceList) || sourceList.isEmpty()) {
            return out;
        }

        String et = exerciseType != null ? exerciseType.trim().toUpperCase(Locale.ROOT) : "";
        double targetAngle = targetAngleByExercise(et);
        boolean hasNewFields = false;
        if (!sourceList.isEmpty() && sourceList.get(0) instanceof Map<?, ?> first) {
            hasNewFields = first.containsKey("depth_level") || first.containsKey("depth_score");
        }

        for (Object item : sourceList) {
            if (!(item instanceof Map<?, ?> eventMap)) {
                continue;
            }
            Map<String, Object> eval = new LinkedHashMap<>();
            int repIndex = parseIntOrDefault(eventMap.get("rep_index"), 0);
            eval.put("repIndex", repIndex);

            if (hasNewFields) {
                eval.put("depthScore", parseDoubleOrNull(eventMap.get("depth_score")));
                eval.put("depthLevel", safeGet(eventMap, "depth_level", "normal"));
                eval.put("tempoMs", parseIntOrDefault(eventMap.get("tempo_ms"), -1));
                eval.put("tempoLevel", safeGet(eventMap, "tempo_level", "unknown"));
                eval.put("stabilityScore", parseDoubleOrNull(eventMap.get("stability_score")));
                eval.put("symmetryDiffDeg", parseDoubleOrNull(eventMap.get("symmetry_diff_deg")));
                eval.put("symmetryLevel", safeGet(eventMap, "symmetry_level", "normal"));
                eval.put("evidence", safeGetOrEmpty(eventMap, "evidence"));
            } else {
                double angle = parseDoubleOrDefault(eventMap.get("min_angle"), 90.0);
                double depthScore = clamp(100.0 - Math.abs(angle - targetAngle) * 1.5, 0.0, 100.0);
                String depthLevel = depthScore >= 75.0 ? "good" : depthScore >= 50.0 ? "warning" : "bad";
                eval.put("depthScore", round1(depthScore));
                eval.put("depthLevel", depthLevel);
                eval.put("tempoMs", -1);
                eval.put("tempoLevel", "unknown");
                eval.put("stabilityScore", 50.0);
                eval.put("symmetryDiffDeg", null);
                eval.put("symmetryLevel", "unknown");
                eval.put("evidence", List.of("最低角度 " + round1(angle) + "°"));
            }

            String tip = eventMap.get("tip") instanceof String s ? s : "";
            eval.put("tip", tip);

            // composite score
            double depthS = eval.get("depthScore") instanceof Number n ? n.doubleValue() : 50.0;
            double stabilityS = eval.get("stabilityScore") instanceof Number n ? n.doubleValue() : 50.0;
            double symS;
            Object symDiff = eval.get("symmetryDiffDeg");
            if (symDiff instanceof Number n) {
                symS = clamp(100.0 - n.doubleValue() * 4.0, 0.0, 100.0);
            } else {
                symS = 50.0;
            }
            double composite = depthS * 0.45 + stabilityS * 0.20 + symS * 0.20;
            // tempo bonus from level
            String tempoLevel = eval.get("tempoLevel") instanceof String s ? s : "normal";
            composite += "normal".equals(tempoLevel) ? 10.0 : "unknown".equals(tempoLevel) ? 5.0 : 3.0;
            composite = clamp(composite, 0.0, 100.0);

            eval.put("score", round1(composite));
            eval.put("level", levelByScore(composite));

            eval.put("diagnosis", buildDiagnosis(eval, hasNewFields));
            eval.put("suggestion", buildSuggestion(eval, et, hasNewFields));

            out.add(eval);
        }
        return out;
    }

    private String buildDiagnosis(Map<String, Object> eval, boolean hasNewFields) {
        StringBuilder sb = new StringBuilder();
        String depthLevel = eval.get("depthLevel") instanceof String s ? s : "normal";
        String tempoLevel = eval.get("tempoLevel") instanceof String s ? s : "normal";
        switch (depthLevel) {
            case "good" -> sb.append("幅度良好");
            case "warning" -> sb.append("幅度一般");
            case "bad" -> sb.append("幅度不足");
            default -> sb.append("幅度正常");
        }
        if (hasNewFields && !"unknown".equals(tempoLevel)) {
            sb.append("，");
            switch (tempoLevel) {
                case "fast" -> sb.append("节奏偏快");
                case "slow" -> sb.append("节奏偏慢");
                case "normal" -> sb.append("节奏稳定");
            }
        }
        return sb.toString();
    }

    private String buildSuggestion(Map<String, Object> eval, String exerciseType, boolean hasNewFields) {
        String depthLevel = eval.get("depthLevel") instanceof String s ? s : "normal";
        String tempoLevel = eval.get("tempoLevel") instanceof String s ? s : "normal";
        if (!hasNewFields) {
            return switch (depthLevel) {
                case "bad" -> "增加动作幅度，控制离心阶段";
                case "warning" -> "适当增加幅度，保持稳定";
                default -> "保持当前动作质量";
            };
        }
        List<String> parts = new ArrayList<>();
        if ("bad".equals(depthLevel)) parts.add("增加动作幅度");
        else if ("warning".equals(depthLevel)) parts.add("适当增加幅度");
        if ("fast".equals(tempoLevel)) parts.add("控制下放速度");
        else if ("slow".equals(tempoLevel)) parts.add("加快动作节奏");
        if (parts.isEmpty()) parts.add("保持当前质量");
        return String.join("，", parts);
    }

    public Map<String, Object> calculateScore(String exerciseType, Map<String, Object> analysis) {
        int repCount = parseIntOrDefault(analysis.get("rep_count"), parseIntOrDefault(analysis.get("repCount"), 0));
        List<Double> minAngles = extractMinAngles(analysis.get("tips"));
        double avgMinAngle = minAngles.isEmpty() ? 0.0 : minAngles.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);

        double targetRep = 10.0;
        double repScore = Math.min(100.0, (repCount / targetRep) * 100.0);

        double targetAngle = targetAngleByExercise(exerciseType);
        double angleDelta = minAngles.isEmpty() ? 50.0 : Math.abs(avgMinAngle - targetAngle);
        double formScore = clamp(100.0 - angleDelta * 1.4, 0.0, 100.0);

        double consistency = 100.0;
        if (minAngles.size() >= 2) {
            double std = stdDev(minAngles);
            consistency = clamp(100.0 - std * 3.5, 0.0, 100.0);
        }

        double rhythmScore = extractRhythmScore(analysis.get("rhythm"));
        double symmetryScore = extractSymmetryScore(analysis.get("symmetry"));

        double finalScore = repScore * 0.15 + formScore * 0.35 + rhythmScore * 0.25 + symmetryScore * 0.15 + consistency * 0.10;
        String level = levelByScore(finalScore);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("finalScore", round1(finalScore));
        out.put("level", level);
        out.put("repScore", round1(repScore));
        out.put("formScore", round1(formScore));
        out.put("rhythmScore", round1(rhythmScore));
        out.put("symmetryScore", round1(symmetryScore));
        out.put("consistencyScore", round1(consistency));
        out.put("repCount", repCount);
        out.put("avgMinAngle", minAngles.isEmpty() ? null : round1(avgMinAngle));
        out.put("targetAngle", round1(targetAngle));
        out.put("computedAt", LocalDateTime.now().toString());
        return out;
    }

    @SuppressWarnings("unchecked")
    private double extractRhythmScore(Object rhythmObj) {
        if (rhythmObj instanceof Map<?, ?> m && m.get("rhythm_score") instanceof Number n) {
            return n.doubleValue();
        }
        return 50.0;
    }

    @SuppressWarnings("unchecked")
    private double extractSymmetryScore(Object symmetryObj) {
        if (symmetryObj instanceof Map<?, ?> m && m.get("symmetry_score") instanceof Number n) {
            return n.doubleValue();
        }
        return 50.0;
    }

    public Map<String, Object> buildTrends(List<ExerciseVideo> videos, int days) {
        int safeDays = Math.max(7, Math.min(days, 180));
        LocalDate cutoff = LocalDate.now().minusDays(safeDays - 1L);

        List<ExerciseVideo> completed = videos.stream()
                .filter(v -> v.getStatus() == ExerciseVideo.VideoStatus.COMPLETED)
                .filter(v -> v.getUploadedAt() != null && !v.getUploadedAt().toLocalDate().isBefore(cutoff))
                .sorted(Comparator.comparing(ExerciseVideo::getUploadedAt))
                .toList();

        Map<LocalDate, List<Map<String, Object>>> grouped = new LinkedHashMap<>();
        for (ExerciseVideo video : completed) {
            Map<String, Object> analysis = parseAnalysis(video.getAnalysisResult());
            Map<String, Object> score = calculateScore(video.getExerciseType(), analysis);
            Map<String, Object> row = new HashMap<>();
            row.put("score", score.get("finalScore"));
            row.put("repCount", parseIntOrDefault(analysis.get("rep_count"), parseIntOrDefault(analysis.get("repCount"), 0)));
            row.put("avgMinAngle", score.get("avgMinAngle"));
            grouped.computeIfAbsent(video.getUploadedAt().toLocalDate(), d -> new ArrayList<>()).add(row);
        }

        List<Map<String, Object>> daily = new ArrayList<>();
        for (Map.Entry<LocalDate, List<Map<String, Object>>> e : grouped.entrySet()) {
            List<Map<String, Object>> rows = e.getValue();
            double avgScore = rows.stream().mapToDouble(r -> parseDoubleOrDefault(r.get("score"), 0.0)).average().orElse(0.0);
            double avgRep = rows.stream().mapToDouble(r -> parseDoubleOrDefault(r.get("repCount"), 0.0)).average().orElse(0.0);
            double avgAngle = rows.stream()
                    .map(r -> r.get("avgMinAngle"))
                    .filter(v -> v != null)
                    .mapToDouble(this::parseDoubleOrDefaultObj)
                    .average().orElse(Double.NaN);

            Map<String, Object> d = new LinkedHashMap<>();
            d.put("date", e.getKey().toString());
            d.put("sessionCount", rows.size());
            d.put("avgScore", round1(avgScore));
            d.put("avgRepCount", round1(avgRep));
            d.put("avgMinAngle", Double.isNaN(avgAngle) ? null : round1(avgAngle));
            daily.add(d);
        }

        List<Map<String, Object>> recentScores = completed.stream()
                .sorted(Comparator.comparing(ExerciseVideo::getUploadedAt).reversed())
                .limit(10)
                .map(v -> {
                    Map<String, Object> analysis = parseAnalysis(v.getAnalysisResult());
                    Map<String, Object> score = calculateScore(v.getExerciseType(), analysis);
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("videoId", v.getId());
                    row.put("exerciseType", v.getExerciseType());
                    row.put("uploadedAt", v.getUploadedAt());
                    row.put("score", score.get("finalScore"));
                    row.put("repCount", score.get("repCount"));
                    row.put("avgMinAngle", score.get("avgMinAngle"));
                    return row;
                })
                .toList();

        double overallScore = recentScores.stream()
                .mapToDouble(r -> parseDoubleOrDefault(r.get("score"), 0.0))
                .average().orElse(0.0);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("days", safeDays);
        out.put("completedSessions", completed.size());
        out.put("overallScore", round1(overallScore));
        out.put("daily", daily);
        out.put("recentScores", recentScores);
        out.put("computedAt", LocalDateTime.now().toString());
        return out;
    }

    public Map<String, Object> parseAnalysis(String analysisJson) {
        if (analysisJson == null || analysisJson.isBlank()) {
            return new HashMap<>();
        }
        try {
            return objectMapper.readValue(analysisJson, new TypeReference<>() {});
        } catch (Exception e) {
            return new HashMap<>();
        }
    }

    private List<Double> extractMinAngles(Object rawTips) {
        List<Double> out = new ArrayList<>();
        if (!(rawTips instanceof List<?> list)) {
            return out;
        }
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> m)) {
                continue;
            }
            Object val = m.get("min_angle");
            if (val == null) {
                continue;
            }
            try {
                out.add(Double.parseDouble(String.valueOf(val)));
            } catch (Exception ignored) {
            }
        }
        return out;
    }

    private double targetAngleByExercise(String exerciseType) {
        String et = exerciseType == null ? "" : exerciseType.trim().toUpperCase(Locale.ROOT);
        return switch (et) {
            case "PUSHUP" -> 80.0;
            case "SQUAT" -> 100.0;
            case "BENCH_PRESS" -> 85.0;
            case "DEADLIFT" -> 120.0;
            case "DUMBBELL_SHOULDER_PRESS" -> 85.0;
            case "DUMBBELL_LATERAL_RAISE" -> 95.0;
            case "DUMBBELL_BICEP_CURL" -> 70.0;
            case "PULL_UP" -> 75.0;
            default -> 90.0;
        };
    }

    private String levelByScore(double score) {
        if (score >= 85.0) return "优秀";
        if (score >= 70.0) return "良好";
        if (score >= 55.0) return "一般";
        return "待提升";
    }

    private double stdDev(List<Double> values) {
        if (values.size() < 2) return 0.0;
        double avg = values.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
        double variance = values.stream().mapToDouble(v -> Math.pow(v - avg, 2)).average().orElse(0.0);
        return Math.sqrt(variance);
    }

    private int parseIntOrDefault(Object val, int defVal) {
        if (val == null) return defVal;
        try {
            return Integer.parseInt(String.valueOf(val));
        } catch (Exception ignored) {
            return defVal;
        }
    }

    private double parseDoubleOrDefault(Object val, double defVal) {
        if (val == null) return defVal;
        try {
            return Double.parseDouble(String.valueOf(val));
        } catch (Exception ignored) {
            return defVal;
        }
    }

    private Double parseDoubleOrNull(Object val) {
        if (val == null) return null;
        try {
            return Double.parseDouble(String.valueOf(val));
        } catch (Exception ignored) {
            return null;
        }
    }

    private double parseDoubleOrDefaultObj(Object val) {
        return parseDoubleOrDefault(val, 0.0);
    }

    private String safeGet(Map<?, ?> map, String key, String defaultValue) {
        Object val = map.get(key);
        return val instanceof String s ? s : defaultValue;
    }

    @SuppressWarnings("unchecked")
    private List<String> safeGetOrEmpty(Map<?, ?> map, String key) {
        Object val = map.get(key);
        return val instanceof List<?> list ? (List<String>) list : List.of();
    }

    private double clamp(double val, double min, double max) {
        return Math.max(min, Math.min(max, val));
    }

    private Double round1(double val) {
        return Math.round(val * 10.0) / 10.0;
    }
}

