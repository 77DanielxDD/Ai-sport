package com.example.aisport.rag;

import java.util.*;

public class RetrievalQuery {

    private String originalQuery;
    private String rewrittenQuery;
    private String route; // training_plan, form_correction, trend_review, general_knowledge
    private String exerciseType;
    private List<String> recentQuestions = List.of();
    private List<String> videoTips = List.of();
    private Map<String, Object> userContext = new LinkedHashMap<>();
    private int topK = 5;
    private String typeFilter;

    public RetrievalQuery() {}

    public String getOriginalQuery() { return originalQuery; }
    public void setOriginalQuery(String originalQuery) { this.originalQuery = originalQuery; }
    public String getRewrittenQuery() { return rewrittenQuery; }
    public void setRewrittenQuery(String rewrittenQuery) { this.rewrittenQuery = rewrittenQuery; }
    public String getRoute() { return route; }
    public void setRoute(String route) { this.route = route; }
    public String getExerciseType() { return exerciseType; }
    public void setExerciseType(String exerciseType) { this.exerciseType = exerciseType; }
    public List<String> getRecentQuestions() { return recentQuestions; }
    public void setRecentQuestions(List<String> recentQuestions) { this.recentQuestions = recentQuestions; }
    public List<String> getVideoTips() { return videoTips; }
    public void setVideoTips(List<String> videoTips) { this.videoTips = videoTips; }
    public Map<String, Object> getUserContext() { return userContext; }
    public void setUserContext(Map<String, Object> userContext) { this.userContext = userContext; }
    public int getTopK() { return topK; }
    public void setTopK(int topK) { this.topK = topK; }
    public String getTypeFilter() { return typeFilter; }
    public void setTypeFilter(String typeFilter) { this.typeFilter = typeFilter; }

    public String effectiveQuery() {
        return rewrittenQuery != null && !rewrittenQuery.isBlank() ? rewrittenQuery : originalQuery;
    }
}
