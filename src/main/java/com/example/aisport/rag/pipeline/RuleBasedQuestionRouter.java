package com.example.aisport.rag.pipeline;

import com.example.aisport.rag.RetrievalQuery;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Set;

@Component
public class RuleBasedQuestionRouter implements QuestionRouter {

    private static final Set<String> PLAN_KEYWORDS = Set.of(
            "计划", "怎么练", "训练方案", "一周", "每天", "安排", "plan", "routine", "schedule", "program"
    );
    private static final Set<String> FORM_KEYWORDS = Set.of(
            "纠正", "错误", "姿势", "动作", "角度", "深度", "幅度", "form", "correct", "fix", "mistake",
            "技术", "technique", "标准", "standard"
    );
    private static final Set<String> TREND_KEYWORDS = Set.of(
            "趋势", "进步", "变化", "最近", "历史", "对比", "trend", "progress", "history", "compare",
            "评分", "分数", "score"
    );

    @Override
    public String route(RetrievalQuery query) {
        String q = query.effectiveQuery().toLowerCase(Locale.ROOT);

        int planScore = countMatches(q, PLAN_KEYWORDS);
        int formScore = countMatches(q, FORM_KEYWORDS);
        int trendScore = countMatches(q, TREND_KEYWORDS);

        // Exercise type context influences routing
        if (query.getExerciseType() != null && !query.getExerciseType().isBlank()) {
            formScore += 1;
        }
        if (query.getVideoTips() != null && !query.getVideoTips().isEmpty()) {
            formScore += 2;
        }
        if (query.getRecentQuestions() != null && !query.getRecentQuestions().isEmpty()) {
            trendScore += 1;
        }

        // All-zero: no keyword matched, fall back to general
        if (formScore == 0 && planScore == 0 && trendScore == 0) {
            return Route.GENERAL_KNOWLEDGE.value();
        }

        if (formScore >= planScore && formScore >= trendScore) {
            return Route.FORM_CORRECTION.value();
        }
        if (trendScore >= planScore) {
            return Route.TREND_REVIEW.value();
        }
        if (planScore > 0) {
            return Route.TRAINING_PLAN.value();
        }
        return Route.GENERAL_KNOWLEDGE.value();
    }

    private int countMatches(String text, Set<String> keywords) {
        int count = 0;
        for (String kw : keywords) {
            if (text.contains(kw)) count++;
        }
        return count;
    }
}
