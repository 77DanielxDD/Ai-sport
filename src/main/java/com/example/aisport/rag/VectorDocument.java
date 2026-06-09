package com.example.aisport.rag;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class VectorDocument {

    private String id;
    private String type;
    private String title;
    private String content;
    private List<Float> vector;
    private String source;

    // Extended fields
    private String chunkHash;
    private String sourceHash;
    private Map<String, Object> metadata = new LinkedHashMap<>();
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public VectorDocument() {}

    public VectorDocument(String id, String type, String title, String content, String source) {
        this.id = id;
        this.type = type;
        this.title = title;
        this.content = content;
        this.source = source;
        this.createdAt = LocalDateTime.now();
        this.updatedAt = this.createdAt;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public List<Float> getVector() { return vector; }
    public void setVector(List<Float> vector) { this.vector = vector; }
    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }

    public String getChunkHash() { return chunkHash; }
    public void setChunkHash(String chunkHash) { this.chunkHash = chunkHash; }
    public String getSourceHash() { return sourceHash; }
    public void setSourceHash(String sourceHash) { this.sourceHash = sourceHash; }
    public Map<String, Object> getMetadata() { return metadata; }
    public void setMetadata(Map<String, Object> metadata) { this.metadata = metadata; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
