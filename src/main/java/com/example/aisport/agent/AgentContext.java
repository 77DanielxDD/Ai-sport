package com.example.aisport.agent;

import java.util.HashMap;
import java.util.Map;

public class AgentContext {

    private final String username;
    private final Long userId;
    private final Long focusVideoId;
    private final String question;
    private final Map<String, Object> attributes = new HashMap<>();

    public AgentContext(String username, Long userId, Long focusVideoId, String question) {
        this.username = username;
        this.userId = userId;
        this.focusVideoId = focusVideoId;
        this.question = question;
    }

    public String getUsername() { return username; }
    public Long getUserId() { return userId; }
    public Long getFocusVideoId() { return focusVideoId; }
    public String getQuestion() { return question; }

    public void setAttribute(String key, Object value) { attributes.put(key, value); }
    @SuppressWarnings("unchecked")
    public <T> T getAttribute(String key) { return (T) attributes.get(key); }
}
