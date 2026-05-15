package com.example.aisport.exception;

import com.example.aisport.service.AnalysisNotReadyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.HttpRequestMethodNotSupportedException;

import java.util.HashMap;
import java.util.Map;

@ControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(AnalysisNotReadyException.class)
    public ResponseEntity<Map<String, Object>> handleAnalysisNotReady(AnalysisNotReadyException ex) {
        Map<String, Object> body = new HashMap<>();
        body.put("videoId", ex.getVideoId());
        body.put("status", ex.getStatus());
        body.put("retryAfterMs", ex.getRetryAfterMs());
        body.put("message", ex.getMessage());
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(body);
    }

    @ExceptionHandler(AnalysisFailedException.class)
    public ResponseEntity<Map<String, Object>> handleAnalysisFailed(AnalysisFailedException ex) {
        Map<String, Object> body = new HashMap<>();
        body.put("videoId", ex.getVideoId());
        body.put("status", ex.getStatus());
        body.put("error", ex.getMessage());
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
    }

    @ExceptionHandler(AnalysisCancelledException.class)
    public ResponseEntity<Map<String, Object>> handleAnalysisCancelled(AnalysisCancelledException ex) {
        Map<String, Object> body = new HashMap<>();
        body.put("videoId", ex.getVideoId());
        body.put("status", ex.getStatus());
        body.put("error", ex.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
    }

    @ExceptionHandler(VideoNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleVideoNotFound(VideoNotFoundException ex) {
        Map<String, Object> body = new HashMap<>();
        body.put("videoId", ex.getVideoId());
        body.put("status", "NOT_FOUND");
        body.put("error", ex.getMessage());
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(body);
    }

    @ExceptionHandler(UnauthorizedAccessException.class)
    public ResponseEntity<Map<String, Object>> handleUnauthorizedAccess(UnauthorizedAccessException ex) {
        Map<String, Object> body = new HashMap<>();
        body.put("videoId", ex.getVideoId());
        body.put("status", "FORBIDDEN");
        body.put("error", ex.getMessage());
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(body);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<Map<String, Object>> handleMethodNotSupported(HttpRequestMethodNotSupportedException ex) {
        Map<String, Object> body = new HashMap<>();
        body.put("status", "METHOD_NOT_ALLOWED");
        body.put("error", "Request method '" + ex.getMethod() + "' is not supported");
        body.put("supportedMethods", ex.getSupportedMethods());
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).body(body);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleUnexpected(Exception ex) {
        Map<String, Object> body = new HashMap<>();
        body.put("status", "INTERNAL_ERROR");
        body.put("error", ex.getMessage());
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
    }
}
