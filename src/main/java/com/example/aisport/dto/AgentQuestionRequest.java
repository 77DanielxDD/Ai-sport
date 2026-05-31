package com.example.aisport.dto;

public class AgentQuestionRequest {
    private String question;
    private Long videoId;

    public String getQuestion() { return question; }
    public void setQuestion(String question) { this.question = question; }
    public Long getVideoId() { return videoId; }
    public void setVideoId(Long videoId) { this.videoId = videoId; }
}
