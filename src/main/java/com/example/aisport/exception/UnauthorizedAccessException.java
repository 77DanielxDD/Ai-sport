package com.example.aisport.exception;

public class UnauthorizedAccessException extends RuntimeException {
    private final Long videoId;

    public UnauthorizedAccessException(Long videoId, String message) {
        super(message);
        this.videoId = videoId;
    }

    public Long getVideoId() {
        return videoId;
    }
}
