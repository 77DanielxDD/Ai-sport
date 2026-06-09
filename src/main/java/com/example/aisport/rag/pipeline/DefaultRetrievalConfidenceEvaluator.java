package com.example.aisport.rag.pipeline;

import com.example.aisport.rag.RetrievalResult;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class DefaultRetrievalConfidenceEvaluator implements RetrievalConfidenceEvaluator {

    private static final double TOP1_MIN_SCORE = 0.35;
    private static final double MIN_GAP = 0.08;
    private static final int MIN_CONTEXTS = 2;

    @Override
    public boolean lowConfidence(List<RetrievalResult> results) {
        if (results == null || results.isEmpty()) return true;

        // Check minimum contexts
        if (results.size() < MIN_CONTEXTS) return true;

        // Check top-1 score
        double top1 = results.get(0).getFinalScore();
        if (top1 < TOP1_MIN_SCORE) return true;

        // Check gap between top-1 and top-2
        if (results.size() >= 2) {
            double top2 = results.get(1).getFinalScore();
            if (Math.abs(top1 - top2) < MIN_GAP) return true;
        }

        return false;
    }

    @Override
    public double confidence(List<RetrievalResult> results) {
        if (results == null || results.isEmpty()) return 0.0;
        double top1 = results.get(0).getFinalScore();
        double diversity = results.size() >= 3 ? 0.2 : 0.0;
        return Math.min(1.0, top1 + diversity);
    }
}
