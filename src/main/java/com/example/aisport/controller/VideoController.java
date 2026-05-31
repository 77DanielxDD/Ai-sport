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
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.security.Principal;
import java.time.LocalDateTime;
import java.util.*;

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
    public ResponseEntity<?> uploadVideo(@RequestParam("file") MultipartFile file,
                                         @RequestParam(value = "username", required = false) String username,
                                         Principal principal,
                                         @RequestParam("exerciseType") String exerciseType) {
        try {
            String effectiveUsername = principal != null ? principal.getName() : username;
            Optional<User> user = userService.findByUsername(effectiveUsername);
            if (user.isEmpty()) return ResponseEntity.badRequest().body(Map.of("error", "User not found"));
            if (file.isEmpty()) return ResponseEntity.badRequest().body(Map.of("error", "Please select a file"));

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

    @PostMapping("/{id}/analyze")
    public ResponseEntity<?> analyzeVideo(@PathVariable Long id, Principal principal) {
        ExerciseVideo video = requireOwnedVideo(id, principal);
        if (video.getStatus() == ExerciseVideo.VideoStatus.PROCESSING)
            return ResponseEntity.badRequest().body(Map.of("error", "Video is processing"));
        video.setStatus(ExerciseVideo.VideoStatus.UPLOADED);
        videoRepository.save(video);
        AnalysisTask task = taskService.createQueuedTask(video.getId());
        videoService.dispatchAnalysisTask(video, task);
        return ResponseEntity.ok(Map.of("message", "Analysis task queued", "videoId", video.getId(), "taskId", task.getId(), "status", "UPLOADED"));
    }

    @GetMapping("/{id}/analysis")
    public ResponseEntity<?> getAnalysis(@PathVariable Long id, Principal principal) throws Exception {
        ExerciseVideo video = requireOwnedVideo(id, principal);
        ExerciseVideo.VideoStatus status = video.getStatus();
        if (status == ExerciseVideo.VideoStatus.UPLOADED || status == ExerciseVideo.VideoStatus.PROCESSING)
            throw new AnalysisNotReadyException(id, status.name(), "Analysis not finished", 1000L);
        if (status == ExerciseVideo.VideoStatus.FAILED)
            throw new AnalysisFailedException(id, status.name(), video.getErrorMessage() == null ? "Analysis failed" : video.getErrorMessage());
        if (status == ExerciseVideo.VideoStatus.CANCELLED)
            throw new AnalysisCancelledException(id, status.name(), video.getErrorMessage() == null ? "Analysis cancelled" : video.getErrorMessage());

        ObjectMapper mapper = new ObjectMapper();
        Map<String, Object> analysisMap = mapper.readValue(videoService.getAnalysisResult(id), new TypeReference<>() {});
        analysisMap.put("trainingScore", trainingInsightService.calculateScore(video.getExerciseType(), analysisMap));
        analysisMap.put("repEvaluations", trainingInsightService.buildRepEvaluations(video.getExerciseType(), analysisMap));

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
        if (leftVideo.getStatus() != ExerciseVideo.VideoStatus.COMPLETED || rightVideo.getStatus() != ExerciseVideo.VideoStatus.COMPLETED)
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                    "error", "Only COMPLETED videos can be compared",
                    "leftStatus", leftVideo.getStatus().name(), "rightStatus", rightVideo.getStatus().name()));

        ObjectMapper mapper = new ObjectMapper();
        Map<String, Object> leftAnalysis = mapper.readValue(videoService.getAnalysisResult(leftId), new TypeReference<>() {});
        Map<String, Object> rightAnalysis = mapper.readValue(videoService.getAnalysisResult(rightId), new TypeReference<>() {});
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
        Map<String, Object> diff = new HashMap<>();
        diff.put("repCountDelta", rightRep - leftRep);
        diff.put("avgMinAngleDelta", leftAvg == null || rightAvg == null ? null : round1(rightAvg - leftAvg));
        diff.put("sameExerciseType", left.get("exerciseType").equals(right.get("exerciseType")));
        diff.put("addedTips", rightSet.stream().filter(t -> !leftSet.contains(t)).toList());
        diff.put("removedTips", leftSet.stream().filter(t -> !rightSet.contains(t)).toList());
        diff.put("summary", buildCompareSummary(leftRep, rightRep, leftAvg, rightAvg));
        return ResponseEntity.ok(Map.of("left", left, "right", right, "diff", diff));
    }

    @PostMapping("/{id}/retry")
    public ResponseEntity<?> retryAnalysis(@PathVariable Long id, Principal principal) {
        ExerciseVideo video = requireOwnedVideo(id, principal);
        if (video.getStatus() != ExerciseVideo.VideoStatus.FAILED && video.getStatus() != ExerciseVideo.VideoStatus.CANCELLED)
            return ResponseEntity.badRequest().body(Map.of("error", "Only FAILED/CANCELLED can retry"));
        videoService.retryVideoAnalysis(video);
        return ResponseEntity.ok(Map.of("message", "Retry queued", "videoId", video.getId()));
    }

    @PostMapping("/{id}/cancel")
    public ResponseEntity<?> cancelAnalysis(@PathVariable Long id, Principal principal) {
        ExerciseVideo video = requireOwnedVideo(id, principal);
        if (video.getStatus() == ExerciseVideo.VideoStatus.COMPLETED)
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("videoId", id, "status", "COMPLETED", "error", "Already completed"));
        if (video.getStatus() == ExerciseVideo.VideoStatus.FAILED)
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("videoId", id, "status", "FAILED", "error", "Already failed"));
        if (video.getStatus() == ExerciseVideo.VideoStatus.CANCELLED)
            return ResponseEntity.ok(Map.of("videoId", id, "status", "CANCELLED", "message", "Already cancelled"));

        AnalysisTask task = taskService.findLatestByVideoId(id).orElse(null);
        if (task == null) return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("videoId", id, "status", "NOT_FOUND", "error", "Task not found"));
        boolean cancelled = taskService.markCancelled(task.getId(), "TASK_CANCELLED", "Task cancelled by user");
        if (!cancelled) return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("videoId", id, "status", task.getStatus().name(), "error", "Task cannot be cancelled"));

        video.setStatus(ExerciseVideo.VideoStatus.CANCELLED);
        video.setProcessedAt(LocalDateTime.now());
        video.setErrorCode("TASK_CANCELLED");
        video.setErrorMessage("Task cancelled by user");
        videoRepository.save(video);
        return ResponseEntity.ok(Map.of("videoId", id, "taskId", task.getId(), "status", "CANCELLED", "message", "Cancel requested"));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> deleteVideo(@PathVariable Long id, Principal principal) {
        ExerciseVideo video = requireOwnedVideo(id, principal);
        videoService.deleteVideoCascade(video);
        return ResponseEntity.ok(Map.of("message", "Video deleted", "videoId", id));
    }

    private ExerciseVideo requireOwnedVideo(Long videoId, Principal principal) {
        if (principal == null) throw new UnauthorizedAccessException(videoId, "Unauthorized");
        ExerciseVideo video = videoService.findById(videoId)
                .orElseThrow(() -> new VideoNotFoundException(videoId, "Video not found"));
        String owner = video.getUser() != null ? video.getUser().getUsername() : null;
        if (owner == null || !owner.equals(principal.getName()))
            throw new UnauthorizedAccessException(videoId, "No permission to access this video");
        return video;
    }

    private Map<String, Object> summarizeForCompare(ExerciseVideo video, Map<String, Object> analysis) {
        int repCount = parseIntOrDefault(analysis.get("rep_count"), parseIntOrDefault(analysis.get("repCount"), 0));
        List<Map<String, Object>> tipsObj = parseTips(analysis.get("tips"));
        List<Double> angles = tipsObj.stream().map(t -> parseDoubleOrNull(t.get("min_angle"))).filter(a -> a != null).toList();
        Double avgMinAngle = angles.isEmpty() ? null : round1(angles.stream().mapToDouble(Double::doubleValue).average().orElse(0));
        List<String> tips = tipsObj.stream().map(t -> t.get("tip")).filter(v -> v != null && !String.valueOf(v).isBlank()).map(String::valueOf).toList();

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

    private int parseIntOrDefault(Object val, int defVal) {
        if (val == null) return defVal;
        try { return Integer.parseInt(String.valueOf(val)); } catch (Exception ignored) { return defVal; }
    }

    private Double parseDoubleOrNull(Object val) {
        if (val == null) return null;
        try { return Double.parseDouble(String.valueOf(val)); } catch (Exception ignored) { return null; }
    }

    private Double round1(Double val) {
        return val == null ? null : Math.round(val * 10.0) / 10.0;
    }

    private String buildCompareSummary(int leftRep, int rightRep, Double leftAvg, Double rightAvg) {
        String repTrend = rightRep > leftRep ? "动作次数增加" : rightRep < leftRep ? "动作次数减少" : "动作次数基本一致";
        String angleTrend = "角度均值暂无可比数据";
        if (leftAvg != null && rightAvg != null) {
            double delta = rightAvg - leftAvg;
            angleTrend = delta > 0.1 ? "角度均值上升" : delta < -0.1 ? "角度均值下降" : "角度均值基本一致";
        }
        return repTrend + "；" + angleTrend;
    }

    private List<Map<String, Object>> parseTips(Object raw) {
        if (!(raw instanceof List<?> list)) return List.of();
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> m) {
                Map<String, Object> tip = new HashMap<>();
                for (Map.Entry<?, ?> e : m.entrySet()) tip.put(String.valueOf(e.getKey()), e.getValue());
                out.add(tip);
            }
        }
        return out;
    }
}
