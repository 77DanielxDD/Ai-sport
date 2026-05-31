package com.example.aisport.service;

import com.example.aisport.entity.ExerciseVideo;
import com.example.aisport.entity.User;
import com.example.aisport.repository.ExerciseVideoRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.util.StreamUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Service
public class RagQaService {

    private final ExerciseVideoRepository videoRepository;
    private final UserService userService;
    private final QueryCacheService queryCacheService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public RagQaService(ExerciseVideoRepository videoRepository,
                        UserService userService,
                        QueryCacheService queryCacheService) {
        this.videoRepository = videoRepository;
        this.userService = userService;
        this.queryCacheService = queryCacheService;
    }

    public String buildPersonalizedAnswer(String username, Long focusVideoId, String question) {
        String q = question == null ? "" : question.trim();
        if (q.isBlank()) {
            throw new IllegalArgumentException("question is required");
        }

        String cacheKey = buildAnswerCacheKey(username, focusVideoId, q);
        Optional<String> cached = queryCacheService.get(cacheKey);
        if (cached.isPresent()) {
            return cached.get();
        }

        User user = userService.findByUsername(username)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));

        List<ExerciseVideo> userVideos = videoRepository.findByUser(user);
        List<ExerciseVideo> recent = userVideos.stream()
                .filter(v -> v.getUploadedAt() != null)
                .sorted(Comparator.comparing(ExerciseVideo::getUploadedAt).reversed())
                .limit(8)
                .toList();

        ExerciseVideo focus = null;
        if (focusVideoId != null) {
            focus = userVideos.stream().filter(v -> focusVideoId.equals(v.getId())).findFirst().orElse(null);
        }
        if (focus == null && !recent.isEmpty()) {
            focus = recent.get(0);
        }

        UserTrainingProfile profile = summarizeUserProfile(recent, focus);
        List<String> retrievedKnowledge = retrieveKnowledge(q, profile);
        String answer = composeAnswer(q, profile, retrievedKnowledge);

        queryCacheService.put(cacheKey, answer, Duration.ofMinutes(2));
        return answer;
    }

    private String buildAnswerCacheKey(String username, Long focusVideoId, String question) {
        String userKey = username == null ? "anonymous" : username.trim().toLowerCase(Locale.ROOT);
        String videoKey = focusVideoId == null ? "latest" : String.valueOf(focusVideoId);
        String questionKey = Integer.toHexString(question.toLowerCase(Locale.ROOT).hashCode());
        return "rag:answer:" + userKey + ":" + videoKey + ":" + questionKey;
    }

    private UserTrainingProfile summarizeUserProfile(List<ExerciseVideo> recent, ExerciseVideo focus) {
        UserTrainingProfile profile = new UserTrainingProfile();
        profile.recentCount = recent.size();

        int completed = 0;
        int failed = 0;
        Set<String> exerciseTypes = new LinkedHashSet<>();
        List<String> issues = new ArrayList<>();

        for (ExerciseVideo video : recent) {
            if (video.getExerciseType() != null && !video.getExerciseType().isBlank()) {
                exerciseTypes.add(video.getExerciseType());
            }
            if (video.getStatus() == ExerciseVideo.VideoStatus.COMPLETED) {
                completed++;
            }
            if (video.getStatus() == ExerciseVideo.VideoStatus.FAILED) {
                failed++;
                if (video.getErrorMessage() != null && !video.getErrorMessage().isBlank()) {
                    issues.add("analysis failed: " + shortText(video.getErrorMessage(), 80));
                }
            }

            Map<String, Object> analysis = parseAnalysis(video.getAnalysisResult());
            Object tipsObj = analysis.get("tips");
            if (tipsObj instanceof List<?> tips) {
                for (Object item : tips) {
                    if (item instanceof Map<?, ?> map) {
                        Object tip = map.get("tip");
                        if (tip != null) {
                            String text = String.valueOf(tip).trim();
                            if (!text.isBlank()) {
                                issues.add(text);
                            }
                        }
                    }
                }
            }
        }

        profile.completedCount = completed;
        profile.failedCount = failed;
        profile.exerciseTypes = new ArrayList<>(exerciseTypes);
        profile.issues = dedupeKeepOrder(issues, 6);
        profile.focusVideo = focus;
        profile.focusAnalysis = focus == null ? Map.of() : parseAnalysis(focus.getAnalysisResult());
        return profile;
    }

    private List<String> retrieveKnowledge(String question, UserTrainingProfile profile) {
        List<String> chunks = loadKnowledgeChunks();
        if (chunks.isEmpty()) {
            return List.of();
        }

        Set<String> keywords = new LinkedHashSet<>(extractKeywords(question));
        for (String type : profile.exerciseTypes) {
            keywords.add(type.toLowerCase(Locale.ROOT));
        }
        for (String issue : profile.issues) {
            keywords.addAll(extractKeywords(issue));
        }

        List<Map.Entry<String, Integer>> scored = new ArrayList<>();
        for (String chunk : chunks) {
            int score = 0;
            String lower = chunk.toLowerCase(Locale.ROOT);
            for (String keyword : keywords) {
                if (keyword.length() >= 2 && lower.contains(keyword)) {
                    score += 2;
                }
            }
            score += Math.min(2, lower.length() / 300);
            scored.add(Map.entry(chunk, score));
        }

        return scored.stream()
                .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
                .limit(3)
                .map(Map.Entry::getKey)
                .toList();
    }

    private String composeAnswer(String question, UserTrainingProfile profile, List<String> knowledge) {
        StringBuilder sb = new StringBuilder();
        sb.append("Question: ").append(question).append("\n\n");
        sb.append("Personalized summary:\n");

        if (profile.recentCount == 0) {
            sb.append("- No recent training records. Starting with general corrective plan.\n");
        } else {
            sb.append("- Recent records: ").append(profile.recentCount)
                    .append(" (completed ").append(profile.completedCount)
                    .append(", failed ").append(profile.failedCount).append(")\n");
            if (!profile.exerciseTypes.isEmpty()) {
                sb.append("- Main exercises: ").append(String.join(", ", profile.exerciseTypes)).append("\n");
            }
            if (!profile.issues.isEmpty()) {
                sb.append("- Common issues:\n");
                for (String issue : profile.issues) {
                    sb.append("  - ").append(issue).append("\n");
                }
            }
        }

        if (profile.focusVideo != null) {
            sb.append("\nFor current report (video #").append(profile.focusVideo.getId()).append("):\n");
            List<String> tips = extractTipsText(profile.focusAnalysis, 3);
            if (tips.isEmpty()) {
                sb.append("- Reduce load and prioritize controlled tempo.\n");
                sb.append("- Keep 1-2 reps in reserve, avoid forcing poor form.\n");
                sb.append("- Record from side view and fix range-of-motion first.\n");
            } else {
                for (String tip : tips) {
                    sb.append("- ").append(rewriteTipToAction(tip)).append("\n");
                }
            }
        }

        sb.append("\n7-day micro plan:\n");
        sb.append("- Day 1-2: technical sets at 50%-60% load, slow eccentric.\n");
        sb.append("- Day 3-4: recover to 65%-75% load, keep stable range-of-motion.\n");
        sb.append("- Day 5-6: maintain load and add one review set.\n");
        sb.append("- Day 7: deload + mobility + weekly review.\n");

        if (!knowledge.isEmpty()) {
            sb.append("\nKnowledge references:\n");
            for (String chunk : knowledge) {
                sb.append("- ").append(shortText(chunk.replace("\n", " "), 140)).append("\n");
            }
        }

        sb.append("\nGenerated at: ").append(LocalDateTime.now());
        return sb.toString();
    }

    private Map<String, Object> parseAnalysis(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (Exception ex) {
            try {
                String inner = objectMapper.readValue(json, String.class);
                return objectMapper.readValue(inner, new TypeReference<>() {});
            } catch (Exception ignored) {
                return Map.of();
            }
        }
    }

    private List<String> loadKnowledgeChunks() {
        try {
            ClassPathResource resource = new ClassPathResource("rag/fitness_knowledge_zh.txt");
            if (!resource.exists()) {
                return List.of();
            }
            String content = StreamUtils.copyToString(resource.getInputStream(), StandardCharsets.UTF_8);
            String[] arr = content.split("\\r?\\n\\r?\\n");
            List<String> out = new ArrayList<>();
            for (String s : arr) {
                String text = s.trim();
                if (!text.isBlank()) {
                    out.add(text);
                }
            }
            return out;
        } catch (IOException e) {
            return List.of();
        }
    }

    private List<String> extractKeywords(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        String normalized = text.toLowerCase(Locale.ROOT)
                .replace("_", " ")
                .replace("-", " ");
        String[] parts = normalized.split("[^a-z0-9\\u4e00-\\u9fa5]+");
        List<String> out = new ArrayList<>();
        for (String part : parts) {
            if (part.length() >= 2) {
                out.add(part);
            }
        }
        return out;
    }

    private List<String> extractTipsText(Map<String, Object> analysis, int limit) {
        List<String> out = new ArrayList<>();
        Object tipsObj = analysis.get("tips");
        if (!(tipsObj instanceof List<?> tips)) {
            return out;
        }
        for (Object item : tips) {
            if (!(item instanceof Map<?, ?> map)) {
                continue;
            }
            Object tip = map.get("tip");
            if (tip == null) {
                continue;
            }
            String text = String.valueOf(tip).trim();
            if (!text.isBlank()) {
                out.add(text);
            }
            if (out.size() >= limit) {
                break;
            }
        }
        return out;
    }

    private String rewriteTipToAction(String tip) {
        String lower = tip.toLowerCase(Locale.ROOT);
        if (lower.contains("depth") || lower.contains("shallow")) {
            return "Increase depth with controlled tempo and reduced load.";
        }
        if (lower.contains("stable") || lower.contains("control") || lower.contains("swing")) {
            return "Tighten core and keep speed consistent without momentum.";
        }
        if (lower.contains("knee") || lower.contains("hip")) {
            return "Keep knee and hip alignment consistent throughout the rep.";
        }
        return "Split each rep into setup, descent, drive, and reset for better control.";
    }

    private List<String> dedupeKeepOrder(List<String> source, int limit) {
        LinkedHashSet<String> set = new LinkedHashSet<>();
        for (String item : source) {
            String text = shortText(item, 120);
            if (!text.isBlank()) {
                set.add(text);
            }
            if (set.size() >= limit) {
                break;
            }
        }
        return new ArrayList<>(set);
    }

    private String shortText(String input, int max) {
        if (input == null) {
            return "";
        }
        String text = input.trim();
        if (text.length() <= max) {
            return text;
        }
        return text.substring(0, Math.max(0, max - 1)) + "...";
    }

    private static class UserTrainingProfile {
        int recentCount;
        int completedCount;
        int failedCount;
        List<String> exerciseTypes = List.of();
        List<String> issues = List.of();
        ExerciseVideo focusVideo;
        Map<String, Object> focusAnalysis = new HashMap<>();
    }
}