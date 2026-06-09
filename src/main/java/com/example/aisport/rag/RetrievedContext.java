package com.example.aisport.rag;

import java.util.LinkedHashMap;
import java.util.Map;

public class RetrievedContext {

    private String source;
    private String title;
    private String chunkId;
    private String content;
    private String snippet;
    private double finalScore;
    private Map<String, Object> metadata = new LinkedHashMap<>();

    public RetrievedContext() {}

    public static RetrievedContext fromResult(RetrievalResult result) {
        RetrievedContext ctx = new RetrievedContext();
        ctx.source = result.source();
        ctx.title = result.title();
        ctx.chunkId = result.chunkId();
        ctx.finalScore = result.getFinalScore();
        ctx.snippet = result.snippet();
        if (result.getDocument() != null) {
            ctx.content = result.getDocument().getContent();
            if (result.getDocument().getMetadata() != null) {
                ctx.metadata = new LinkedHashMap<>(result.getDocument().getMetadata());
            }
        }
        return ctx;
    }

    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getChunkId() { return chunkId; }
    public void setChunkId(String chunkId) { this.chunkId = chunkId; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public String getSnippet() { return snippet; }
    public void setSnippet(String snippet) { this.snippet = snippet; }
    public double getFinalScore() { return finalScore; }
    public void setFinalScore(double finalScore) { this.finalScore = finalScore; }
    public Map<String, Object> getMetadata() { return metadata; }
    public void setMetadata(Map<String, Object> metadata) { this.metadata = metadata; }

    public String referenceLabel() {
        return source + "#" + chunkId;
    }
}
