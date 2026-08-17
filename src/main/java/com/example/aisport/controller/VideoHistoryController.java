package com.example.aisport.controller;

import com.example.aisport.entity.ExerciseVideo;
import com.example.aisport.entity.User;
import com.example.aisport.exception.UnauthorizedAccessException;
import com.example.aisport.service.TrainingInsightService;
import com.example.aisport.service.UserService;
import com.example.aisport.service.VideoService;
import com.example.aisport.task.AnalysisTaskService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/videos")
public class VideoHistoryController {

    private final VideoService videoService;
    private final UserService userService;
    private final TrainingInsightService trainingInsightService;
    private final AnalysisTaskService taskService;
    private final Counter pollRequestCounter;

    public VideoHistoryController(VideoService videoService,
                                   UserService userService,
                                   TrainingInsightService trainingInsightService,
                                   AnalysisTaskService taskService,
                                   MeterRegistry meterRegistry) {
        this.videoService = videoService;
        this.userService = userService;
        this.trainingInsightService = trainingInsightService;
        this.taskService = taskService;
        this.pollRequestCounter = meterRegistry.counter("task_poll_request_total");
    }

    @GetMapping
    public ResponseEntity<?> getAllVideos(@RequestParam(required = false) String username,
                                          @RequestParam(required = false) String status,
                                          @RequestParam(required = false) String exerciseType,
                                          @RequestParam(required = false) Integer page,
                                          @RequestParam(required = false) Integer size,
                                          Principal principal) {
        String effectiveUsername = principal != null ? principal.getName() : username;
        if (effectiveUsername == null || effectiveUsername.isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "Unauthorized"));
        }
        Optional<User> user = userService.findByUsername(effectiveUsername);
        if (user.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "User not found"));
        }
        List<ExerciseVideo> videos = videoService.findByUser(user.get());
        List<ExerciseVideo> filtered = videos.stream()
                .filter(v -> matchStatus(v, status))
                .filter(v -> matchExerciseType(v, exerciseType))
                .collect(Collectors.toList());

        List<Map<String, Object>> rows = filtered.stream().map(this::toVideoSummary).collect(Collectors.toList());

        if (page != null || size != null) {
            int p = page == null || page < 0 ? 0 : page;
            int s = size == null || size <= 0 ? 20 : Math.min(size, 200);
            int from = p * s;
            int to = Math.min(from + s, rows.size());
            List<Map<String, Object>> items = from >= rows.size() ? List.of() : rows.subList(from, to);
            return ResponseEntity.ok(Map.of("items", items, "total", rows.size(), "page", p, "size", s));
        }
        return ResponseEntity.ok(rows);
    }

    @GetMapping("/performance/summary")
    public ResponseEntity<?> performanceSummary(Principal principal) {
        if (principal == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "Unauthorized"));
        }
        User user = userService.findByUsername(principal.getName())
                .orElseThrow(() -> new UnauthorizedAccessException(null, "User not found"));
        List<ExerciseVideo> videos = videoService.findByUser(user);
        List<Long> videoIds = videos.stream().map(ExerciseVideo::getId).toList();
        Map<String, Object> summary = taskService.summarizeForVideoIds(videoIds);
        summary.put("username", principal.getName());
        return ResponseEntity.ok(summary);
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> getVideoById(@PathVariable Long id, Principal principal) {
        ExerciseVideo video = requireOwnedVideo(id, principal);
        return ResponseEntity.ok(toVideoSummary(video));
    }

    @GetMapping("/{id}/status")
    public ResponseEntity<?> getVideoStatus(@PathVariable Long id, Principal principal) {
        pollRequestCounter.increment();
        ExerciseVideo v = requireOwnedVideo(id, principal);
        Map<String, Object> response = new HashMap<>();
        response.put("videoId", v.getId());
        response.put("status", v.getStatus().name());
        response.put("exerciseType", v.getExerciseType());
        response.put("uploadedAt", v.getUploadedAt());
        response.put("processedAt", v.getProcessedAt());
        response.put("errorCode", v.getErrorCode());
        response.put("errorMessage", v.getErrorMessage());
        // 自适应轮询建议：前端按此值退避，避免写死 1 秒。终态为 0。
        long retryAfterMs = 0L;
        ExerciseVideo.VideoStatus st = v.getStatus();
        if (st == ExerciseVideo.VideoStatus.UPLOADED) {
            retryAfterMs = 1000L;
        } else if (st == ExerciseVideo.VideoStatus.PROCESSING) {
            retryAfterMs = 1500L;
        }
        response.put("retryAfterMs", retryAfterMs);
        taskService.findLatestByVideoId(id).ifPresent(t -> {
            response.put("latestTaskId", t.getId());
            response.put("taskStatus", t.getStatus().name());
            response.put("queuedAt", t.getQueuedAt());
            response.put("startedAt", t.getStartedAt());
            response.put("finishedAt", t.getFinishedAt());
            if (t.getQueuedAt() != null) {
                LocalDateTime end = t.getFinishedAt() != null ? t.getFinishedAt() : LocalDateTime.now();
                response.put("endToEndMs", java.time.Duration.between(t.getQueuedAt(), end).toMillis());
            }
        });
        return ResponseEntity.ok(response);
    }

    @DeleteMapping
    public ResponseEntity<?> deleteAllMyVideos(@RequestParam(required = false) String status,
                                               @RequestParam(required = false) String exerciseType,
                                               Principal principal) {
        if (principal == null) {
            throw new UnauthorizedAccessException(null, "Unauthorized");
        }
        User user = userService.findByUsername(principal.getName())
                .orElseThrow(() -> new UnauthorizedAccessException(null, "User not found"));
        List<ExerciseVideo> videos = videoService.findByUser(user).stream()
                .filter(v -> matchStatus(v, status))
                .filter(v -> matchExerciseType(v, exerciseType))
                .collect(Collectors.toList());
        int deleted = 0;
        for (ExerciseVideo video : videos) {
            videoService.deleteVideoCascade(video);
            deleted++;
        }
        return ResponseEntity.ok(Map.of("message", "All videos deleted", "deletedCount", deleted,
                "filters", Map.of("status", status == null ? "ALL" : status,
                        "exerciseType", exerciseType == null ? "ALL" : exerciseType)));
    }

    private ExerciseVideo requireOwnedVideo(Long videoId, Principal principal) {
        if (principal == null) throw new UnauthorizedAccessException(videoId, "Unauthorized");
        ExerciseVideo video = videoService.findById(videoId)
                .orElseThrow(() -> new com.example.aisport.exception.VideoNotFoundException(videoId, "Video not found"));
        String owner = video.getUser() != null ? video.getUser().getUsername() : null;
        if (owner == null || !owner.equals(principal.getName()))
            throw new UnauthorizedAccessException(videoId, "No permission to access this video");
        return video;
    }

    private Map<String, Object> toVideoSummary(ExerciseVideo v) {
        Map<String, Object> row = new HashMap<>();
        row.put("id", v.getId());
        row.put("exerciseType", v.getExerciseType());
        row.put("status", v.getStatus().name());
        row.put("uploadedAt", v.getUploadedAt());
        row.put("processedAt", v.getProcessedAt());
        row.put("originalFileName", v.getOriginalFileName());
        row.put("fileSizeMb", v.getFileSizeMb());
        row.put("durationSeconds", v.getDurationSeconds());
        row.put("errorCode", v.getErrorCode());
        row.put("errorMessage", v.getErrorMessage());
        row.put("analysisSchemaVersion", v.getAnalysisSchemaVersion());
        if (v.getStatus() == ExerciseVideo.VideoStatus.COMPLETED) {
            Map<String, Object> analysis = trainingInsightService.parseAnalysis(v.getAnalysisResult());
            row.put("trainingScore", trainingInsightService.calculateScore(v.getExerciseType(), analysis).get("finalScore"));
        } else {
            row.put("trainingScore", null);
        }
        return row;
    }

    private boolean matchStatus(ExerciseVideo v, String rawStatus) {
        if (rawStatus == null || rawStatus.isBlank()) return true;
        return v.getStatus() != null && v.getStatus().name().equals(rawStatus.trim().toUpperCase(Locale.ROOT));
    }

    private boolean matchExerciseType(ExerciseVideo v, String rawType) {
        if (rawType == null || rawType.isBlank()) return true;
        String expect = rawType.trim().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
        String actual = v.getExerciseType() == null ? "" : v.getExerciseType().trim().toUpperCase(Locale.ROOT);
        if ("BENCHPRESS".equals(expect)) expect = "BENCH_PRESS";
        else if ("DEAD_LIFT".equals(expect)) expect = "DEADLIFT";
        else if ("SHOULDER_PRESS".equals(expect) || "DUMBBELL_PRESS".equals(expect) || "DUMBBELL_OVERHEAD_PRESS".equals(expect)) expect = "DUMBBELL_SHOULDER_PRESS";
        else if ("LATERAL_RAISE".equals(expect) || "SIDE_RAISE".equals(expect) || "DUMBBELL_SIDE_RAISE".equals(expect)) expect = "DUMBBELL_LATERAL_RAISE";
        else if ("BICEP_CURL".equals(expect) || "BICEPS_CURL".equals(expect) || "DUMBBELL_CURL".equals(expect)) expect = "DUMBBELL_BICEP_CURL";
        else if ("PULLUP".equals(expect) || "CHINUP".equals(expect) || "CHIN_UP".equals(expect)) expect = "PULL_UP";
        return actual.equals(expect);
    }
}
