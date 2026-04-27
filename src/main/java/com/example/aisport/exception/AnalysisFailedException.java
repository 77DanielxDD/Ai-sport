package com.example.aisport.exception;

public class AnalysisFailedException extends RuntimeException {
    private final Long videoId;
    private final String status;

    public AnalysisFailedException(Long videoId, String status, String message) {
        super(message);
        this.videoId = videoId;
        this.status = status;
    }

    public Long getVideoId() {
        return videoId;
    }

    public String getStatus() {
        return status;
    }
}