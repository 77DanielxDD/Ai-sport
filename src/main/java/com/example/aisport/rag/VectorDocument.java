package com.example.aisport.rag;

import java.util.List;

public class VectorDocument {

    private String id;
    private String type;
    private String title;
    private String content;
    private List<Float> vector;
    private String source;

    public VectorDocument() {}

    public VectorDocument(String id, String type, String title, String content, String source) {
        this.id = id;
        this.type = type;
        this.title = title;
        this.content = content;
        this.source = source;
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
}
