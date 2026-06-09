package com.example.aisport.service;

import com.example.aisport.entity.ExerciseVideo;
import com.example.aisport.entity.User;
import com.example.aisport.rag.*;
import com.example.aisport.rag.pipeline.*;
import com.example.aisport.repository.ExerciseVideoRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;

@Service
public class RagQaService {

    private final ExerciseVideoRepository videoRepository;
    private final UserService userService;
    private final QueryCacheService queryCacheService;
    private final QueryRewriter queryRewriter;
    private final HybridRetriever hybridRetriever;
    private final Reranker reranker;
    private final ContextAssembler contextAssembler;
    private final RetrievalConfidenceEvaluator confidenceEvaluator;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public RagQaService(ExerciseVideoRepository videoRepository,
                        UserService userService,
                        QueryCacheService queryCacheService,
                        QueryRewriter queryRewriter,
                        HybridRetriever hybridRetriever,
                        Reranker reranker,
                        ContextAssembler contextAssembler,
                        RetrievalConfidenceEvaluator confidenceEvaluator) {
        this.videoRepository = videoRepository;
        this.userService = userService;
        this.queryCacheService = queryCacheService;
        this.queryRewriter = queryRewriter;
        this.hybridRetriever = hybridRetriever;
        this.reranker = reranker;
        this.contextAssembler = contextAssembler;
        this.confidenceEvaluator = confidenceEvaluator;
    }

    public String buildPersonalizedAnswer(String username, Long focusVideoId, String question) {
        String q = question == null ? "" : question.trim();
        if (q.isBlank()) {
            throw new IllegalArgumentException("question is required");
        }

        // Check answer cache
        String answerCacheKey = buildAnswerCacheKey(username, focusVideoId, q);
        Optional<String> cachedAnswer = queryCacheService.get(answerCacheKey);
        if (cachedAnswer.isPresent()) {
            return cachedAnswer.get();
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

        // Pipeline: rewrite -> retrieve -> rerank -> confidence check -> assemble
        List<RetrievedContext> contexts = retrieveWithPipeline(q, profile);
        String answer = composeAnswer(q, profile, contexts);

        queryCacheService.put(answerCacheKey, answer, Duration.ofMinutes(2));
        return answer;
    }

    private List<RetrievedContext> retrieveWithPipeline(String question, UserTrainingProfile profile) {
        // Step 1: Query rewrite (with cache)
        String rewriteCacheKey = buildRewriteCacheKey(question);
        Optional<String> cachedRewrite = queryCacheService.get(rewriteCacheKey);

        RetrievalQuery rq = queryRewriter.rewrite(question, buildProfileMap(profile));
        if (cachedRewrite.isPresent()) {
            rq.setRewrittenQuery(cachedRewrite.get());
        } else {
            queryCacheService.put(rewriteCacheKey, rq.effectiveQuery(), Duration.ofMinutes(10));
        }

        // Step 2: Retrieve (with cache)
        String retCacheKey = buildRetrievalCacheKey(profile, rq.effectiveQuery());
        Optional<String> cachedRet = queryCacheService.get(retCacheKey);
        List<RetrievalResult> results;

        if (cachedRet.isPresent()) {
            results = deserializeResults(cachedRet.get());
        } else {
            results = hybridRetriever.retrieve(rq);

            // Step 3: Rerank
            results = reranker.rerank(rq, results);

            // Step 4: Low confidence -> second retrieval
            if (confidenceEvaluator.lowConfidence(results)) {
                List<RetrievalResult> expanded = hybridRetriever.retrieveExpanded(rq);
                // Merge and deduplicate
                Map<String, RetrievalResult> merged = new LinkedHashMap<>();
                for (RetrievalResult r : results) {
                    if (r.chunkId() != null) merged.put(r.chunkId(), r);
                }
                for (RetrievalResult r : expanded) {
                    if (r.chunkId() != null) merged.putIfAbsent(r.chunkId(), r);
                }
                results = new ArrayList<>(merged.values());
                results = reranker.rerank(rq, results);
            }

            // Cache retrieval results
            queryCacheService.put(retCacheKey, serializeResults(results), Duration.ofMinutes(3));
        }

        // Step 5: Context assembly
        return contextAssembler.assemble(rq, results);
    }

    private String buildAnswerCacheKey(String username, Long focusVideoId, String question) {
        String userKey = username == null ? "anonymous" : username.trim().toLowerCase(Locale.ROOT);
        String videoKey = focusVideoId == null ? "latest" : String.valueOf(focusVideoId);
        String questionHash = Integer.toHexString(question.toLowerCase(Locale.ROOT).hashCode());
        return "rag:answer:" + userKey + ":" + videoKey + ":" + questionHash;
    }

    private String buildRewriteCacheKey(String question) {
        String qHash = Integer.toHexString(question.toLowerCase(Locale.ROOT).hashCode());
        return "rag:rewrite:" + qHash;
    }

    private String buildRetrievalCacheKey(UserTrainingProfile profile, String query) {
        String videoPart = profile.focusVideo == null ? "latest" : String.valueOf(profile.focusVideo.getId());
        String typePart = Integer.toHexString(String.join(",", profile.exerciseTypes).hashCode());
        String issuePart = Integer.toHexString(String.join("|", profile.issues).hashCode());
        String qHash = Integer.toHexString(query.toLowerCase(Locale.ROOT).hashCode());
        return "rag:retrieval:" + videoPart + ":" + typePart + ":" + issuePart + ":" + qHash;
    }

    private Map<String, Object> buildProfileMap(UserTrainingProfile profile) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("recentCount", profile.recentCount);
        map.put("completedCount", profile.completedCount);
        map.put("failedCount", profile.failedCount);
        map.put("exerciseTypes", profile.exerciseTypes);
        map.put("issues", profile.issues);
        if (profile.focusVideo != null) {
            map.put("focusVideoId", profile.focusVideo.getId());
            map.put("focusExerciseType", profile.focusVideo.getExerciseType());
        }
        return map;
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

    private String composeAnswer(String question, UserTrainingProfile profile, List<RetrievedContext> contexts) {
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

        if (!contexts.isEmpty()) {
            sb.append("\nReference sources:\n");
            for (int i = 0; i < contexts.size(); i++) {
                RetrievedContext ctx = contexts.get(i);
                sb.append("[").append(i + 1).append("] ")
                        .append(ctx.getTitle())
                        .append(" - ").append(ctx.referenceLabel()).append("\n");
                sb.append("    ").append(ctx.getSnippet()).append("\n");
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

    private String serializeResults(List<RetrievalResult> results) {
        try {
            return objectMapper.writeValueAsString(results);
        } catch (Exception e) {
            return "[]";
        }
    }

    private List<RetrievalResult> deserializeResults(String json) {
        try {
            return objectMapper.readValue(json,
                    objectMapper.getTypeFactory().constructCollectionType(List.class, RetrievalResult.class));
        } catch (Exception e) {
            return List.of();
        }
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
