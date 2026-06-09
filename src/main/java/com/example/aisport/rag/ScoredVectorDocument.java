package com.example.aisport.rag;

import java.util.LinkedHashMap;
import java.util.Map;

public class ScoredVectorDocument {

    private VectorDocument document;
    private double vectorScore;
    private double keywordScore;
    private double rerankScore;
    private double finalScore;
    private String matchedBy; // "vector", "keyword", "both"
    private Map<String, Object> metadata = new LinkedHashMap<>();

    public ScoredVectorDocument() {}

    public ScoredVectorDocument(VectorDocument document, double vectorScore, String matchedBy) {
        this.document = document;
        this.vectorScore = vectorScore;
        this.keywordScore = 0.0;
        this.rerankScore = 0.0;
        this.finalScore = vectorScore;
        this.matchedBy = matchedBy;
    }

    public VectorDocument getDocument() { return document; }
    public void setDocument(VectorDocument document) { this.document = document; }
    public double getVectorScore() { return vectorScore; }
    public void setVectorScore(double vectorScore) { this.vectorScore = vectorScore; }
    public double getKeywordScore() { return keywordScore; }
    public void setKeywordScore(double keywordScore) { this.keywordScore = keywordScore; }
    public double getRerankScore() { return rerankScore; }
    public void setRerankScore(double rerankScore) { this.rerankScore = rerankScore; }
    public double getFinalScore() { return finalScore; }
    public void setFinalScore(double finalScore) { this.finalScore = finalScore; }
    public String getMatchedBy() { return matchedBy; }
    public void setMatchedBy(String matchedBy) { this.matchedBy = matchedBy; }
    public Map<String, Object> getMetadata() { return metadata; }
    public void setMetadata(Map<String, Object> metadata) { this.metadata = metadata; }

    public static ScoredVectorDocument fromVector(VectorDocument doc, double score) {
        ScoredVectorDocument s = new ScoredVectorDocument();
        s.document = doc;
        s.vectorScore = score;
        s.finalScore = score;
        s.matchedBy = "vector";
        return s;
    }
}
