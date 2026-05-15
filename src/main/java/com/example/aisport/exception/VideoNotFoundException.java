package com.example.aisport.exception;

public class VideoNotFoundException extends RuntimeException {
    private final Long videoId;

    public VideoNotFoundException(Long videoId, String message) {
        super(message);
        this.videoId = videoId;
    }

    public Long getVideoId() {
        return videoId;
    }
}