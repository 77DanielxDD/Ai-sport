package com.example.aisport.rag;

import java.util.LinkedHashMap;
import java.util.Map;

public class RetrievalResult {

    private VectorDocument document;
    private double vectorScore;
    private double keywordScore;
    private double rerankScore;
    private double finalScore;
    private String matchedBy;
    private Map<String, Object> metadata = new LinkedHashMap<>();

    public RetrievalResult() {}

    public static RetrievalResult fromScored(ScoredVectorDocument scored) {
        RetrievalResult r = new RetrievalResult();
        r.document = scored.getDocument();
        r.vectorScore = scored.getVectorScore();
        r.keywordScore = scored.getKeywordScore();
        r.rerankScore = scored.getRerankScore();
        r.finalScore = scored.getFinalScore();
        r.matchedBy = scored.getMatchedBy();
        r.metadata = scored.getMetadata();
        return r;
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

    public String chunkId() { return document != null ? document.getId() : null; }
    public String source() { return document != null ? document.getSource() : null; }
    public String title() { return document != null ? document.getTitle() : null; }
    public String snippet() {
        if (document == null || document.getContent() == null) return "";
        String c = document.getContent();
        return c.length() > 140 ? c.substring(0, 140) + "..." : c;
    }
}
