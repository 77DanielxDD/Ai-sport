package com.example.aisport.rag.pipeline;

import com.example.aisport.rag.RetrievalQuery;
import org.springframework.stereotype.Component;

import java.util.*;

@Component
public class RuleBasedQueryRewriter implements QueryRewriter {

    private static final Map<String, String> EXERCISE_ALIASES = new LinkedHashMap<>();
    static {
        EXERCISE_ALIASES.put("深蹲", "深蹲");
        EXERCISE_ALIASES.put("squat", "深蹲");
        EXERCISE_ALIASES.put("deep squat", "深蹲");
        EXERCISE_ALIASES.put("卧推", "卧推");
        EXERCISE_ALIASES.put("bench press", "卧推");
        EXERCISE_ALIASES.put("bench", "卧推");
        EXERCISE_ALIASES.put("硬拉", "硬拉");
        EXERCISE_ALIASES.put("deadlift", "硬拉");
        EXERCISE_ALIASES.put("俯卧撑", "俯卧撑");
        EXERCISE_ALIASES.put("push up", "俯卧撑");
        EXERCISE_ALIASES.put("pushup", "俯卧撑");
        EXERCISE_ALIASES.put("引体向上", "引体向上");
        EXERCISE_ALIASES.put("pull up", "引体向上");
        EXERCISE_ALIASES.put("pullup", "引体向上");
        EXERCISE_ALIASES.put("划船", "划船");
        EXERCISE_ALIASES.put("row", "划船");
    }

    @Override
    public RetrievalQuery rewrite(String question, Map<String, Object> userProfile) {
        return rewriteWithContext(question, userProfile, null, List.of(), List.of());
    }

    @Override
    @SuppressWarnings("unchecked")
    public RetrievalQuery rewriteWithContext(String question, Map<String, Object> userProfile,
                                              String exerciseType, List<String> videoTips,
                                              List<String> recentQuestions) {
        RetrievalQuery rq = new RetrievalQuery();
        rq.setOriginalQuery(question);

        String normalized = normalize(question);
        rq.setRewrittenQuery(normalized);
        rq.setExerciseType(normalizeExerciseType(exerciseType));
        rq.setVideoTips(videoTips != null ? videoTips : List.of());
        rq.setRecentQuestions(recentQuestions != null ? recentQuestions : List.of());
        rq.setTopK(5);

        // Expand with user context
        Map<String, Object> ctx = new LinkedHashMap<>();
        if (userProfile != null) {
            Object types = userProfile.get("exerciseTypes");
            if (types instanceof List<?> list && !list.isEmpty()) {
                ctx.put("exerciseTypes", list);
                if (rq.getExerciseType() == null) {
                    rq.setExerciseType(normalizeExerciseType(String.valueOf(list.get(0))));
                }
            }
            Object issues = userProfile.get("issues");
            if (issues instanceof List<?> ilist && !ilist.isEmpty()) {
                ctx.put("issues", ilist);
            }
        }
        rq.setUserContext(ctx);

        // Route
        rq.setRoute(new RuleBasedQuestionRouter().route(rq));

        return rq;
    }

    private String normalize(String query) {
        if (query == null || query.isBlank()) return "";
        String q = query.trim()
                .replace("_", " ")
                .replace("-", " ")
                .replaceAll("\\s+", " ");

        // Replace English exercise names with Chinese
        String lower = q.toLowerCase(Locale.ROOT);
        for (Map.Entry<String, String> e : EXERCISE_ALIASES.entrySet()) {
            if (!e.getKey().equals(e.getValue()) && lower.contains(e.getKey())) {
                q = q.replaceAll("(?i)" + Pattern.quote(e.getKey()), e.getValue());
            }
        }
        return q;
    }

    private String normalizeExerciseType(String type) {
        if (type == null || type.isBlank()) return null;
        String lower = type.trim().toLowerCase(Locale.ROOT);
        return EXERCISE_ALIASES.getOrDefault(lower, type.trim());
    }

    // Simple regex-escaping without Pattern class dependency in loop
    private static String regexQuote(String s) {
        return s.replaceAll("([.+*?^$\\[\\]{}()|\\\\])", "\\\\$1");
    }

    private static class Pattern {
        static String quote(String s) {
            return s.replaceAll("([.+*?^$\\[\\]{}()|\\\\])", "\\\\$1");
        }
    }
}
