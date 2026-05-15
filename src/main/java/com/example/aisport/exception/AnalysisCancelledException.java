package com.example.aisport.exception;

public class AnalysisCancelledException extends RuntimeException {
    private final Long videoId;
    private final String status;

    public AnalysisCancelledException(Long videoId, String status, String message) {
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
