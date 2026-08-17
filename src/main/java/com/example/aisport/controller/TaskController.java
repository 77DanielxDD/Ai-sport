package com.example.aisport.controller;

import com.example.aisport.entity.ExerciseVideo;
import com.example.aisport.exception.UnauthorizedAccessException;
import com.example.aisport.exception.VideoNotFoundException;
import com.example.aisport.service.VideoService;
import com.example.aisport.task.AnalysisTask;
import com.example.aisport.task.AnalysisTaskService;
import com.example.aisport.task.TaskEventBroadcaster;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.security.Principal;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/tasks")
public class TaskController {

    private final AnalysisTaskService taskService;
    private final VideoService videoService;
    private final TaskEventBroadcaster eventBroadcaster;

    public TaskController(AnalysisTaskService taskService, VideoService videoService,
                          TaskEventBroadcaster eventBroadcaster) {
        this.taskService = taskService;
        this.videoService = videoService;
        this.eventBroadcaster = eventBroadcaster;
    }

    @GetMapping("/{taskId}")
    public ResponseEntity<?> getTask(@PathVariable Long taskId, Principal principal) {
        return taskService.findById(taskId)
                .<ResponseEntity<?>>map(t -> {
                    requireOwnedVideo(t.getVideoId(), principal);
                    return ResponseEntity.ok(toTaskView(t));
                })
                .orElseGet(() -> ResponseEntity.status(404).body(Map.of("error", "Task not found", "taskId", taskId)));
    }

    /** SSE 事件流：任务状态变化实时推送，前端断线后回退轮询。 */
    @GetMapping("/video/{videoId}/events")
    public SseEmitter streamVideoEvents(@PathVariable Long videoId, Principal principal) {
        requireOwnedVideo(videoId, principal);
        return eventBroadcaster.subscribe(videoId);
    }

    @GetMapping("/video/{videoId}")
    public ResponseEntity<?> listVideoTasks(@PathVariable Long videoId, Principal principal) {
        requireOwnedVideo(videoId, principal);
        List<Map<String, Object>> items = taskService.listByVideoId(videoId).stream()
                .map(this::toTaskView)
                .collect(Collectors.toList());
        return ResponseEntity.ok(Map.of(
                "videoId", videoId,
                "count", items.size(),
                "items", items
        ));
    }

    private void requireOwnedVideo(Long videoId, Principal principal) {
        if (principal == null) {
            throw new UnauthorizedAccessException(videoId, "Unauthorized");
        }
        ExerciseVideo video = videoService.findById(videoId)
                .orElseThrow(() -> new VideoNotFoundException(videoId, "Video not found"));
        String owner = video.getUser() != null ? video.getUser().getUsername() : null;
        if (owner == null || !owner.equals(principal.getName())) {
            throw new UnauthorizedAccessException(videoId, "No permission to access this video tasks");
        }
    }

    private Map<String, Object> toTaskView(AnalysisTask t) {
        Map<String, Object> out = new HashMap<>();
        out.put("id", t.getId());
        out.put("videoId", t.getVideoId());
        out.put("status", t.getStatus().name());
        out.put("attempt", t.getAttempt());
        out.put("queuedAt", t.getQueuedAt());
        out.put("startedAt", t.getStartedAt());
        out.put("finishedAt", t.getFinishedAt());
        out.put("errorCode", t.getErrorCode());
        out.put("errorMessage", t.getErrorMessage());
        out.put("correlationId", t.getCorrelationId());

        LocalDateTime now = LocalDateTime.now();
        Long queueMs = null;
        Long runMs = null;
        Long totalMs = null;
        if (t.getQueuedAt() != null) {
            LocalDateTime startRef = t.getStartedAt() != null ? t.getStartedAt() : now;
            queueMs = Duration.between(t.getQueuedAt(), startRef).toMillis();
            LocalDateTime finishRef = t.getFinishedAt() != null ? t.getFinishedAt() : now;
            totalMs = Duration.between(t.getQueuedAt(), finishRef).toMillis();
        }
        if (t.getStartedAt() != null) {
            LocalDateTime finishRef = t.getFinishedAt() != null ? t.getFinishedAt() : now;
            runMs = Duration.between(t.getStartedAt(), finishRef).toMillis();
        }
        out.put("queueMs", queueMs);
        out.put("runMs", runMs);
        out.put("totalMs", totalMs);

        boolean canCancel = t.getStatus() == AnalysisTask.TaskStatus.QUEUED || t.getStatus() == AnalysisTask.TaskStatus.PROCESSING;
        out.put("canCancel", canCancel);

        // 自适应轮询建议：前端按此值拉取，避免写死 1 秒。终态返回 0 表示无需轮询。
        out.put("retryAfterMs", retryAfterMs(t));
        return out;
    }

    /** 根据任务状态给出下一次轮询建议：QUEUED/PROCESSING 退避，终态 0。 */
    private long retryAfterMs(AnalysisTask t) {
        switch (t.getStatus()) {
            case QUEUED:
                return 1000L;
            case PROCESSING:
                return 1500L;
            default:
                return 0L;
        }
    }
}
