package com.example.aisport.controller;

import com.example.aisport.entity.ExerciseVideo;
import com.example.aisport.entity.User;
import com.example.aisport.exception.AnalysisCancelledException;
import com.example.aisport.exception.AnalysisFailedException;
import com.example.aisport.exception.UnauthorizedAccessException;
import com.example.aisport.exception.VideoNotFoundException;
import com.example.aisport.repository.ExerciseVideoRepository;
import com.example.aisport.service.AnalysisNotReadyException;
import com.example.aisport.service.TrainingInsightService;
import com.example.aisport.service.UserService;
import com.example.aisport.service.VideoService;
import com.example.aisport.task.AnalysisTask;
import com.example.aisport.task.AnalysisTaskService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.security.Principal;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.Locale;

@RestController
@RequestMapping("/api/videos")
public class VideoController {

    private final VideoService videoService;
    private final UserService userService;
    private final ExerciseVideoRepository videoRepository;
    private final AnalysisTaskService taskService;
    private final TrainingInsightService trainingInsightService;

    public VideoController(VideoService videoService,
                           UserService userService,
                           ExerciseVideoRepository videoRepository,
                           AnalysisTaskService taskService,
                           TrainingInsightService trainingInsightService) {
        this.videoService = videoService;
        this.userService = userService;
        this.videoRepository = videoRepository;
        this.taskService = taskService;
        this.trainingInsightService = trainingInsightService;
    }

    @PostMapping("/upload")
    public ResponseEntity<?> uploadVideo(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "username", required = false) String username,
            Principal principal,
            @RequestParam("exerciseType") String exerciseType) {

        try {
            String effectiveUsername = principal != null ? principal.getName() : username;
            Optional<User> user = userService.findByUsername(effectiveUsername);
            if (user.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("error", "User not found"));
            }

            if (file.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("error", "Please select a file"));
            }

            ExerciseVideo savedVideo = videoService.saveVideo(file, user.get(), exerciseType);

            Long latestTaskId = taskService.findLatestByVideoId(savedVideo.getId()).map(AnalysisTask::getId).orElse(null);

            Map<String, Object> response = new HashMap<>();
            response.put("message", "Upload success, analysis queued");
            response.put("videoId", savedVideo.getId());
            response.put("taskId", latestTaskId);
            response.put("status", savedVideo.getStatus().name());
            response.put("estimatedTime", "~5-20 seconds");
            response.put("checkStatusUrl", "/api/videos/" + savedVideo.getId() + "/status");

            return ResponseEntity.ok(response);

        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (IOException e) {
            return ResponseEntity.internalServerError().body(Map.of("error", "Save failed: " + e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", "Upload failed: " + e.getMessage()));
        }
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

        List<Map<String, Object>> rows = filtered
                .stream()
                .map(this::toVideoSummary)
                .collect(Collectors.toList());

        if (page != null || size != null) {
            int p = page == null || page < 0 ? 0 : page;
            int s = size == null || size <= 0 ? 20 : Math.min(size, 200);
            int from = p * s;
            int to = Math.min(from + s, rows.size());
            List<Map<String, Object>> items = from >= rows.size() ? List.of() : rows.subList(from, to);
            return ResponseEntity.ok(Map.of(
                    "items", items,
                    "total", rows.size(),
                    "page", p,
                    "size", s
            ));
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
        ExerciseVideo v = requireOwnedVideo(id, principal);
        Map<String, Object> response = new HashMap<>();
        response.put("videoId", v.getId());
        response.put("status", v.getStatus().name());
        response.put("exerciseType", v.getExerciseType());
        response.put("uploadedAt", v.getUploadedAt());
        response.put("processedAt", v.getProcessedAt());
        response.put("errorCode", v.getErrorCode());
        response.put("errorMessage", v.getErrorMessage());

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

    @PostMapping("/{id}/analyze")
    public ResponseEntity<?> analyzeVideo(@PathVariable Long id, Principal principal) {
        ExerciseVideo video = requireOwnedVideo(id, principal);
        if (video.getStatus() == ExerciseVideo.VideoStatus.PROCESSING) {
            return ResponseEntity.badRequest().body(Map.of("error", "Video is processing"));
        }

        video.setStatus(ExerciseVideo.VideoStatus.UPLOADED);
        videoRepository.save(video);

        AnalysisTask task = taskService.createQueuedTask(video.getId());
        videoService.dispatchAnalysisTask(video, task);

        return ResponseEntity.ok(Map.of(
                "message", "Analysis task queued",
                "videoId", video.getId(),
                "taskId", task.getId(),
                "status", "UPLOADED"
        ));
    }

    @GetMapping("/{id}/analysis")
    public ResponseEntity<?> getAnalysis(@PathVariable Long id, Principal principal) throws Exception {
        ExerciseVideo video = requireOwnedVideo(id, principal);

        ExerciseVideo.VideoStatus status = video.getStatus();
        if (status == ExerciseVideo.VideoStatus.UPLOADED || status == ExerciseVideo.VideoStatus.PROCESSING) {
            throw new AnalysisNotReadyException(id, status.name(), "Analysis not finished", 1000L);
        }
        if (status == ExerciseVideo.VideoStatus.FAILED) {
            throw new AnalysisFailedException(id, status.name(), video.getErrorMessage() == null ? "Analysis failed" : video.getErrorMessage());
        }
        if (status == ExerciseVideo.VideoStatus.CANCELLED) {
            throw new AnalysisCancelledException(id, status.name(), video.getErrorMessage() == null ? "Analysis cancelled" : video.getErrorMessage());
        }

        Map<String, Object> analysisMap = parseAnalysisJson(videoService.getAnalysisResult(id));
        analysisMap.put("trainingScore", trainingInsightService.calculateScore(video.getExerciseType(), analysisMap));

        Map<String, Object> response = new HashMap<>();
        response.put("videoId", id);
        response.put("status", status.name());
        response.put("retrievedAt", LocalDateTime.now());
        response.put("analysis", analysisMap);
        taskService.findLatestByVideoId(id).ifPresent(t -> {
            response.put("taskId", t.getId());
            if (t.getQueuedAt() != null) {
                LocalDateTime end = t.getFinishedAt() != null ? t.getFinishedAt() : LocalDateTime.now();
                response.put("endToEndMs", java.time.Duration.between(t.getQueuedAt(), end).toMillis());
            }
        });
        return ResponseEntity.ok(response);
    }

    @GetMapping("/compare")
    public ResponseEntity<?> compareAnalysis(@RequestParam("leftId") Long leftId,
                                             @RequestParam("rightId") Long rightId,
                                             Principal principal) throws Exception {
        ExerciseVideo leftVideo = requireOwnedVideo(leftId, principal);
        ExerciseVideo rightVideo = requireOwnedVideo(rightId, principal);

        if (leftVideo.getStatus() != ExerciseVideo.VideoStatus.COMPLETED
                || rightVideo.getStatus() != ExerciseVideo.VideoStatus.COMPLETED) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                    "error", "Only COMPLETED videos can be compared",
                    "leftStatus", leftVideo.getStatus().name(),
                    "rightStatus", rightVideo.getStatus().name()
            ));
        }

        Map<String, Object> leftAnalysis = parseAnalysisJson(videoService.getAnalysisResult(leftId));
        Map<String, Object> rightAnalysis = parseAnalysisJson(videoService.getAnalysisResult(rightId));

        Map<String, Object> left = summarizeForCompare(leftVideo, leftAnalysis);
        Map<String, Object> right = summarizeForCompare(rightVideo, rightAnalysis);

        int leftRep = (int) left.get("repCount");
        int rightRep = (int) right.get("repCount");
        Double leftAvg = (Double) left.get("avgMinAngle");
        Double rightAvg = (Double) right.get("avgMinAngle");

        @SuppressWarnings("unchecked")
        List<String> leftTips = (List<String>) left.get("tips");
        @SuppressWarnings("unchecked")
        List<String> rightTips = (List<String>) right.get("tips");

        Set<String> leftSet = new LinkedHashSet<>(leftTips);
        Set<String> rightSet = new LinkedHashSet<>(rightTips);
        List<String> addedTips = rightSet.stream().filter(t -> !leftSet.contains(t)).toList();
        List<String> removedTips = leftSet.stream().filter(t -> !rightSet.contains(t)).toList();

        Map<String, Object> diff = new HashMap<>();
        diff.put("repCountDelta", rightRep - leftRep);
        diff.put("avgMinAngleDelta", leftAvg == null || rightAvg == null ? null : round1(rightAvg - leftAvg));
        diff.put("sameExerciseType", left.get("exerciseType").equals(right.get("exerciseType")));
        diff.put("addedTips", addedTips);
        diff.put("removedTips", removedTips);
        diff.put("summary", buildCompareSummary(leftRep, rightRep, leftAvg, rightAvg));

        return ResponseEntity.ok(Map.of(
                "left", left,
                "right", right,
                "diff", diff
        ));
    }

    @GetMapping("/trends")
    public ResponseEntity<?> getTrainingTrends(@RequestParam(required = false, defaultValue = "30") Integer days,
                                               Principal principal) {
        if (principal == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "Unauthorized"));
        }
        User user = userService.findByUsername(principal.getName())
                .orElseThrow(() -> new UnauthorizedAccessException(null, "User not found"));
        List<ExerciseVideo> videos = videoService.findByUser(user);
        Map<String, Object> trends = trainingInsightService.buildTrends(videos, days == null ? 30 : days);
        trends.put("username", principal.getName());
        return ResponseEntity.ok(trends);
    }

    @PostMapping("/{id}/retry")
    public ResponseEntity<?> retryAnalysis(@PathVariable Long id, Principal principal) {
        ExerciseVideo video = requireOwnedVideo(id, principal);
        if (video.getStatus() != ExerciseVideo.VideoStatus.FAILED && video.getStatus() != ExerciseVideo.VideoStatus.CANCELLED) {
            return ResponseEntity.badRequest().body(Map.of("error", "Only FAILED/CANCELLED can retry"));
        }

        videoService.retryVideoAnalysis(video);
        return ResponseEntity.ok(Map.of("message", "Retry queued", "videoId", video.getId()));
    }

    @PostMapping("/{id}/cancel")
    public ResponseEntity<?> cancelAnalysis(@PathVariable Long id, Principal principal) {
        ExerciseVideo video = requireOwnedVideo(id, principal);
        if (video.getStatus() == ExerciseVideo.VideoStatus.COMPLETED) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("videoId", id, "status", "COMPLETED", "error", "Already completed"));
        }
        if (video.getStatus() == ExerciseVideo.VideoStatus.FAILED) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("videoId", id, "status", "FAILED", "error", "Already failed"));
        }
        if (video.getStatus() == ExerciseVideo.VideoStatus.CANCELLED) {
            return ResponseEntity.ok(Map.of("videoId", id, "status", "CANCELLED", "message", "Already cancelled"));
        }

        AnalysisTask task = taskService.findLatestByVideoId(id)
                .orElse(null);
        if (task == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("videoId", id, "status", "NOT_FOUND", "error", "Task not found"));
        }

        boolean cancelled = taskService.markCancelled(task.getId(), "TASK_CANCELLED", "Task cancelled by user");
        if (!cancelled) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                    "videoId", id,
                    "status", task.getStatus().name(),
                    "error", "Task cannot be cancelled"
            ));
        }

        video.setStatus(ExerciseVideo.VideoStatus.CANCELLED);
        video.setProcessedAt(LocalDateTime.now());
        video.setErrorCode("TASK_CANCELLED");
        video.setErrorMessage("Task cancelled by user");
        videoRepository.save(video);

        return ResponseEntity.ok(Map.of(
                "videoId", id,
                "taskId", task.getId(),
                "status", "CANCELLED",
                "message", "Cancel requested"
        ));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> deleteVideo(@PathVariable Long id, Principal principal) {
        ExerciseVideo video = requireOwnedVideo(id, principal);
        videoService.deleteVideoCascade(video);
        return ResponseEntity.ok(Map.of(
                "message", "Video deleted",
                "videoId", id
        ));
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

        List<ExerciseVideo> videos = videoService.findByUser(user)
                .stream()
                .filter(v -> matchStatus(v, status))
                .filter(v -> matchExerciseType(v, exerciseType))
                .collect(Collectors.toList());
        int deleted = 0;
        for (ExerciseVideo video : videos) {
            videoService.deleteVideoCascade(video);
            deleted++;
        }
        return ResponseEntity.ok(Map.of(
                "message", "All videos deleted",
                "deletedCount", deleted,
                "filters", Map.of(
                        "status", status == null ? "ALL" : status,
                        "exerciseType", exerciseType == null ? "ALL" : exerciseType
                )
        ));
    }

    private ExerciseVideo requireOwnedVideo(Long videoId, Principal principal) {
        if (principal == null) {
            throw new UnauthorizedAccessException(videoId, "Unauthorized");
        }
        ExerciseVideo video = videoService.findById(videoId)
                .orElseThrow(() -> new VideoNotFoundException(videoId, "Video not found"));
        String owner = video.getUser() != null ? video.getUser().getUsername() : null;
        if (owner == null || !owner.equals(principal.getName())) {
            throw new UnauthorizedAccessException(videoId, "No permission to access this video");
        }
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
        if (rawStatus == null || rawStatus.isBlank()) {
            return true;
        }
        String expect = rawStatus.trim().toUpperCase(Locale.ROOT);
        return v.getStatus() != null && v.getStatus().name().equals(expect);
    }

    private boolean matchExerciseType(ExerciseVideo v, String rawType) {
        if (rawType == null || rawType.isBlank()) {
            return true;
        }
        String expect = rawType.trim().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
        String actual = v.getExerciseType() == null ? "" : v.getExerciseType().trim().toUpperCase(Locale.ROOT);
        if ("BENCHPRESS".equals(expect)) {
            expect = "BENCH_PRESS";
        } else if ("DEAD_LIFT".equals(expect)) {
            expect = "DEADLIFT";
        } else if ("SHOULDER_PRESS".equals(expect) || "DUMBBELL_PRESS".equals(expect)
                || "DUMBBELL_OVERHEAD_PRESS".equals(expect)) {
            expect = "DUMBBELL_SHOULDER_PRESS";
        } else if ("LATERAL_RAISE".equals(expect) || "SIDE_RAISE".equals(expect)
                || "DUMBBELL_SIDE_RAISE".equals(expect)) {
            expect = "DUMBBELL_LATERAL_RAISE";
        } else if ("BICEP_CURL".equals(expect) || "BICEPS_CURL".equals(expect)
                || "DUMBBELL_CURL".equals(expect)) {
            expect = "DUMBBELL_BICEP_CURL";
        } else if ("PULLUP".equals(expect) || "CHINUP".equals(expect) || "CHIN_UP".equals(expect)) {
            expect = "PULL_UP";
        }
        return actual.equals(expect);
    }

    private Map<String, Object> summarizeForCompare(ExerciseVideo video, Map<String, Object> analysis) {
        int repCount = parseIntOrDefault(analysis.get("rep_count"), parseIntOrDefault(analysis.get("repCount"), 0));
        List<Map<String, Object>> tipsObj = parseTips(analysis.get("tips"));
        List<Double> angles = tipsObj.stream()
                .map(t -> parseDoubleOrNull(t.get("min_angle")))
                .filter(a -> a != null)
                .toList();
        Double avgMinAngle = angles.isEmpty() ? null : round1(angles.stream().mapToDouble(Double::doubleValue).average().orElse(0));
        List<String> tips = tipsObj.stream()
                .map(t -> t.get("tip"))
                .filter(v -> v != null && !String.valueOf(v).isBlank())
                .map(String::valueOf)
                .toList();

        Map<String, Object> out = new HashMap<>();
        out.put("videoId", video.getId());
        out.put("exerciseType", video.getExerciseType());
        out.put("repCount", repCount);
        out.put("avgMinAngle", avgMinAngle);
        out.put("tips", tips);
        out.put("tipsCount", tips.size());
        out.put("uploadedAt", video.getUploadedAt());
        out.put("trainingScore", trainingInsightService.calculateScore(video.getExerciseType(), analysis).get("finalScore"));
        return out;
    }

    private List<Map<String, Object>> parseTips(Object raw) {
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        List<Map<String, Object>> out = new java.util.ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> m) {
                Map<String, Object> tip = new HashMap<>();
                for (Map.Entry<?, ?> e : m.entrySet()) {
                    tip.put(String.valueOf(e.getKey()), e.getValue());
                }
                out.add(tip);
            }
        }
        return out;
    }

    private int parseIntOrDefault(Object val, int defVal) {
        if (val == null) {
            return defVal;
        }
        try {
            return Integer.parseInt(String.valueOf(val));
        } catch (Exception ignored) {
            return defVal;
        }
    }

    private Map<String, Object> parseAnalysisJson(String rawJson) throws JsonProcessingException {
        ObjectMapper mapper = new ObjectMapper();
        try {
            return mapper.readValue(rawJson, new TypeReference<>() {});
        } catch (MismatchedInputException ex) {
            // Compatibility for DBs/drivers that return JSON column as a JSON string value.
            String inner = mapper.readValue(rawJson, String.class);
            return mapper.readValue(inner, new TypeReference<>() {});
        }
    }

    private Double parseDoubleOrNull(Object val) {
        if (val == null) {
            return null;
        }
        try {
            return Double.parseDouble(String.valueOf(val));
        } catch (Exception ignored) {
            return null;
        }
    }

    private Double round1(Double val) {
        if (val == null) {
            return null;
        }
        return Math.round(val * 10.0) / 10.0;
    }

    private String buildCompareSummary(int leftRep, int rightRep, Double leftAvg, Double rightAvg) {
        String repTrend;
        if (rightRep > leftRep) {
            repTrend = "动作次数增加";
        } else if (rightRep < leftRep) {
            repTrend = "动作次数减少";
        } else {
            repTrend = "动作次数基本一致";
        }

        String angleTrend = "角度均值暂无可比数据";
        if (leftAvg != null && rightAvg != null) {
            double delta = rightAvg - leftAvg;
            if (delta > 0.1) {
                angleTrend = "角度均值上升";
            } else if (delta < -0.1) {
                angleTrend = "角度均值下降";
            } else {
                angleTrend = "角度均值基本一致";
            }
        }
        return repTrend + "；" + angleTrend;
    }
}
