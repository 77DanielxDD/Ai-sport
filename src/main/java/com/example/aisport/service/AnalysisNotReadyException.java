package com.example.aisport.service;

public class AnalysisNotReadyException extends RuntimeException {
    private final Long videoId;
    private final String status;
    private final long retryAfterMs;

    public AnalysisNotReadyException(Long videoId, String status, String message, long retryAfterMs) {
        super(message);
        this.videoId = videoId;
        this.status = status;
        this.retryAfterMs = retryAfterMs;
    }

    public Long getVideoId() {
        return videoId;
    }

    public String getStatus() {
        return status;
    }

    public long getRetryAfterMs() {
        return retryAfterMs;
    }
}