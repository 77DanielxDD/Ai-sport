package com.example.aisport.agent;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class AgentAnswer {

    private String summary;
    private List<DiagnosisItem> diagnosis = new ArrayList<>();
    private List<Recommendation> recommendations = new ArrayList<>();
    private List<TrainingPlanItem> trainingPlan = new ArrayList<>();
    private List<ToolCallRecord> toolCalls = new ArrayList<>();
    private List<ReferenceItem> references = new ArrayList<>();

    public String getSummary() { return summary; }
    public void setSummary(String summary) { this.summary = summary; }
    public List<DiagnosisItem> getDiagnosis() { return diagnosis; }
    public void setDiagnosis(List<DiagnosisItem> diagnosis) { this.diagnosis = diagnosis; }
    public List<Recommendation> getRecommendations() { return recommendations; }
    public void setRecommendations(List<Recommendation> recommendations) { this.recommendations = recommendations; }
    public List<TrainingPlanItem> getTrainingPlan() { return trainingPlan; }
    public void setTrainingPlan(List<TrainingPlanItem> trainingPlan) { this.trainingPlan = trainingPlan; }
    public List<ToolCallRecord> getToolCalls() { return toolCalls; }
    public void setToolCalls(List<ToolCallRecord> toolCalls) { this.toolCalls = toolCalls; }
    public List<ReferenceItem> getReferences() { return references; }
    public void setReferences(List<ReferenceItem> references) { this.references = references; }

    public static class DiagnosisItem {
        private String issue;
        private String evidence;
        private String severity;

        public String getIssue() { return issue; }
        public void setIssue(String issue) { this.issue = issue; }
        public String getEvidence() { return evidence; }
        public void setEvidence(String evidence) { this.evidence = evidence; }
        public String getSeverity() { return severity; }
        public void setSeverity(String severity) { this.severity = severity; }
    }

    public static class Recommendation {
        private String title;
        private String detail;
        private String priority;

        public String getTitle() { return title; }
        public void setTitle(String title) { this.title = title; }
        public String getDetail() { return detail; }
        public void setDetail(String detail) { this.detail = detail; }
        public String getPriority() { return priority; }
        public void setPriority(String priority) { this.priority = priority; }
    }

    public static class TrainingPlanItem {
        private String day;
        private String content;
        private String focus;

        public String getDay() { return day; }
        public void setDay(String day) { this.day = day; }
        public String getContent() { return content; }
        public void setContent(String content) { this.content = content; }
        public String getFocus() { return focus; }
        public void setFocus(String focus) { this.focus = focus; }
    }

    public static class ReferenceItem {
        private String type;
        private String title;
        private String snippet;

        public String getType() { return type; }
        public void setType(String type) { this.type = type; }
        public String getTitle() { return title; }
        public void setTitle(String title) { this.title = title; }
        public String getSnippet() { return snippet; }
        public void setSnippet(String snippet) { this.snippet = snippet; }
    }
}
