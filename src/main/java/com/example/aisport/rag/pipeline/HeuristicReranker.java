package com.example.aisport.rag.pipeline;

import com.example.aisport.rag.RetrievalQuery;
import com.example.aisport.rag.RetrievalResult;
import org.springframework.stereotype.Component;

import java.util.*;

@Component
public class HeuristicReranker implements Reranker {

    @Override
    public List<RetrievalResult> rerank(RetrievalQuery query, List<RetrievalResult> results) {
        if (results == null || results.isEmpty()) return List.of();

        String exerciseType = query.getExerciseType();
        List<String> videoTips = query.getVideoTips();
        List<String> recentQuestions = query.getRecentQuestions();

        for (RetrievalResult r : results) {
            double bonus = 0.0;

            if (r.getDocument() != null) {
                String title = r.getDocument().getTitle() != null
                        ? r.getDocument().getTitle().toLowerCase(Locale.ROOT) : "";
                String content = r.getDocument().getContent() != null
                        ? r.getDocument().getContent().toLowerCase(Locale.ROOT) : "";

                // Title match bonus
                if (exerciseType != null && title.contains(exerciseType.toLowerCase(Locale.ROOT))) {
                    bonus += 0.15;
                }

                // Content depth bonus: longer relevant content gets slight boost
                if (content.length() > 200) bonus += 0.05;

                // Video tips relevance
                if (videoTips != null) {
                    for (String tip : videoTips) {
                        String lowerTip = tip.toLowerCase(Locale.ROOT);
                        if (lowerTip.length() > 4 && content.contains(lowerTip.substring(0, Math.min(lowerTip.length(), 10)))) {
                            bonus += 0.08;
                            break;
                        }
                    }
                }

                // Recent question relevance
                if (recentQuestions != null) {
                    for (String rq : recentQuestions) {
                        String lowerRq = rq.toLowerCase(Locale.ROOT);
                        if (lowerRq.length() > 4 && content.contains(lowerRq.substring(0, Math.min(lowerRq.length(), 10)))) {
                            bonus += 0.05;
                            break;
                        }
                    }
                }
            }

            // Route-based bonus
            if (query.getRoute() != null) {
                String route = query.getRoute();
                if ("form_correction".equals(route) && r.getDocument() != null
                        && r.getDocument().getContent() != null) {
                    String c = r.getDocument().getContent().toLowerCase(Locale.ROOT);
                    if (c.contains("纠正") || c.contains("建议") || c.contains("correct") || c.contains("improve")) {
                        bonus += 0.10;
                    }
                }
                if ("training_plan".equals(route) && r.getDocument() != null
                        && r.getDocument().getContent() != null) {
                    String c = r.getDocument().getContent().toLowerCase(Locale.ROOT);
                    if (c.contains("训练") || c.contains("组") || c.contains("负重") || c.contains("计划")) {
                        bonus += 0.10;
                    }
                }
            }

            double newFinal = r.getFinalScore() + bonus;
            r.setRerankScore(newFinal);
            r.setFinalScore(Math.min(1.0, newFinal));
        }

        // Deduplicate by document ID, keep highest score
        Map<String, RetrievalResult> deduped = new LinkedHashMap<>();
        for (RetrievalResult r : results) {
            String id = r.chunkId();
            if (id == null) continue;
            RetrievalResult existing = deduped.get(id);
            if (existing == null || r.getFinalScore() > existing.getFinalScore()) {
                deduped.put(id, r);
            }
        }

        List<RetrievalResult> out = new ArrayList<>(deduped.values());
        out.sort((a, b) -> Double.compare(b.getFinalScore(), a.getFinalScore()));
        return out;
    }
}
