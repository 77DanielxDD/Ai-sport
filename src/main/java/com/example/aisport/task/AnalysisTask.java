package com.example.aisport.task;

import jakarta.persistence.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "analysis_tasks")
public class AnalysisTask {

    public enum TaskStatus {
        QUEUED,
        PROCESSING,
        COMPLETED,
        FAILED,
        CANCELLED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long videoId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private TaskStatus status = TaskStatus.QUEUED;

    @Column(nullable = false)
    private Integer attempt = 1;

    private LocalDateTime queuedAt;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;

    @Column(length = 64)
    private String errorCode;

    @Column(columnDefinition = "TEXT")
    private String errorMessage;

    @Column(length = 128)
    private String correlationId;

    // 乐观锁：并发状态迁移时以 @Version 保证原子性（重复消费/重复消息只迁移一次）
    @Version
    private Long version;

    public Long getId() { return id; }
    public Long getVersion() { return version; }
    public void setVersion(Long version) { this.version = version; }
    public void setId(Long id) { this.id = id; }
    public Long getVideoId() { return videoId; }
    public void setVideoId(Long videoId) { this.videoId = videoId; }
    public TaskStatus getStatus() { return status; }
    public void setStatus(TaskStatus status) { this.status = status; }
    public Integer getAttempt() { return attempt; }
    public void setAttempt(Integer attempt) { this.attempt = attempt; }
    public LocalDateTime getQueuedAt() { return queuedAt; }
    public void setQueuedAt(LocalDateTime queuedAt) { this.queuedAt = queuedAt; }
    public LocalDateTime getStartedAt() { return startedAt; }
    public void setStartedAt(LocalDateTime startedAt) { this.startedAt = startedAt; }
    public LocalDateTime getFinishedAt() { return finishedAt; }
    public void setFinishedAt(LocalDateTime finishedAt) { this.finishedAt = finishedAt; }
    public String getErrorCode() { return errorCode; }
    public void setErrorCode(String errorCode) { this.errorCode = errorCode; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
    public String getCorrelationId() { return correlationId; }
    public void setCorrelationId(String correlationId) { this.correlationId = correlationId; }
}
