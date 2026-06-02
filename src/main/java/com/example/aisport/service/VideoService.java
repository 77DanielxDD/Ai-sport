package com.example.aisport.service;

import com.example.aisport.entity.ExerciseVideo;
import com.example.aisport.entity.User;
import com.example.aisport.repository.ExerciseVideoRepository;
import com.example.aisport.memory.UserMemoryRefreshService;
import com.example.aisport.service.mq.VideoAnalysisProducer;
import com.example.aisport.task.AnalysisTask;
import com.example.aisport.task.AnalysisTaskService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.util.*;

@Service
public class VideoService {
    private static final Logger log = LoggerFactory.getLogger(VideoService.class);

    private static final Set<String> SUPPORTED_EXERCISE_TYPES = Set.of(
            "PUSHUP",
            "SQUAT",
            "BENCH_PRESS",
            "DEADLIFT",
            "DUMBBELL_SHOULDER_PRESS",
            "DUMBBELL_LATERAL_RAISE",
            "DUMBBELL_BICEP_CURL",
            "PULL_UP"
    );

    private final ExerciseVideoRepository videoRepository;
    private final VideoAnalysisProducer videoAnalysisProducer;
    private final AnalysisFallbackDispatcher analysisFallbackDispatcher;
    private final RestTemplate restTemplate;
    private final AnalysisTaskService taskService;
    private final ObjectStorageService objectStorageService;
    private final VideoStorageService storageService;
    private final LLMInsightService llmInsightService;
    private final TrainingInsightService trainingInsightService;
    private final UserMemoryRefreshService userMemoryRefreshService;

    private final ObjectMapper mapper = new ObjectMapper();

    private final String aiBaseUrl;
    private final String aiAnalyzePath;
    private final boolean fallbackLocalAsyncWhenMqDown;

    public VideoService(ExerciseVideoRepository videoRepository,
                        VideoAnalysisProducer videoAnalysisProducer,
                        @Lazy AnalysisFallbackDispatcher analysisFallbackDispatcher,
                        RestTemplate restTemplate,
                        AnalysisTaskService taskService,
                        ObjectStorageService objectStorageService,
                        VideoStorageService storageService,
                        TrainingInsightService trainingInsightService,
                        Optional<LLMInsightService> llmInsightService,
                        UserMemoryRefreshService userMemoryRefreshService,
                        @Value("${ai.service.base-url:http://127.0.0.1:8000}") String aiBaseUrl,
                        @Value("${ai.python.analyze-path:/analyze}") String aiAnalyzePath,
                        @Value("${app.analysis.fallback-local-async-when-mq-down:true}") boolean fallbackLocalAsyncWhenMqDown) {
        this.videoRepository = videoRepository;
        this.videoAnalysisProducer = videoAnalysisProducer;
        this.analysisFallbackDispatcher = analysisFallbackDispatcher;
        this.restTemplate = restTemplate;
        this.taskService = taskService;
        this.objectStorageService = objectStorageService;
        this.storageService = storageService;
        this.trainingInsightService = trainingInsightService;
        this.llmInsightService = llmInsightService.orElse(null);
        this.userMemoryRefreshService = userMemoryRefreshService;
        this.aiBaseUrl = aiBaseUrl;
        this.aiAnalyzePath = aiAnalyzePath;
        this.fallbackLocalAsyncWhenMqDown = fallbackLocalAsyncWhenMqDown;
    }

    public List<ExerciseVideo> findAll() {
        return videoRepository.findAll();
    }

    public Optional<ExerciseVideo> findById(Long id) {
        return videoRepository.findById(id);
    }

    public List<ExerciseVideo> findByUser(User user) {
        return videoRepository.findByUser(user);
    }

    public List<ExerciseVideo> findUploadedBefore(LocalDateTime cutoff) {
        return videoRepository.findByUploadedAtBefore(cutoff);
    }

    public String getAnalysisResult(Long videoId) {
        ExerciseVideo video = videoRepository.findById(videoId)
                .orElseThrow(() -> new EntityNotFoundException("Video not found"));

        if (video.getStatus() == ExerciseVideo.VideoStatus.FAILED) {
            throw new IllegalStateException("Video analysis failed");
        }
        if (video.getStatus() != ExerciseVideo.VideoStatus.COMPLETED) {
            throw new AnalysisNotReadyException(videoId, video.getStatus().name(), "Analysis not ready", 1000L);
        }
        if (video.getAnalysisResult() == null || video.getAnalysisResult().isBlank()) {
            throw new IllegalStateException("Analysis result is empty");
        }
        return video.getAnalysisResult();
    }

    public ExerciseVideo saveVideo(MultipartFile file, User user, String exerciseType) throws IOException {
        return saveVideo(file, user, exerciseType, null);
    }

    public ExerciseVideo saveVideo(MultipartFile file, User user, String exerciseType, Double durationSeconds) throws IOException {
        String normalizedType = normalizeExerciseType(exerciseType);
        String original = storageService.safeOriginalFilename(file.getOriginalFilename());
        String fileName = System.currentTimeMillis() + "_" + original;
        String storedPath;
        if (objectStorageService.isEnabled()) {
            Path tmp = Files.createTempFile("aisport-upload-", "-" + fileName);
            try {
                Files.copy(file.getInputStream(), tmp, StandardCopyOption.REPLACE_EXISTING);
                String key = objectStorageService.buildVideoObjectKey(user.getId(), fileName);
                objectStorageService.uploadFile(tmp, key, file.getContentType());
                storedPath = objectStorageService.toCosUri(key);
            } finally {
                storageService.safeDeleteFile(tmp.toString());
            }
        } else {
            Path targetLocation = storageService.getVideoStorageLocation().resolve(fileName);
            Files.copy(file.getInputStream(), targetLocation, StandardCopyOption.REPLACE_EXISTING);
            storedPath = targetLocation.toString();
        }

        ExerciseVideo video = new ExerciseVideo();
        video.setUser(user);
        video.setOriginalFileName(original);
        video.setStoredFilePath(storedPath);
        video.setExerciseType(normalizedType);
        video.setStatus(ExerciseVideo.VideoStatus.UPLOADED);
        video.setFileSizeMb(file.getSize() / (1024.0 * 1024.0));
        video.setUploadedAt(LocalDateTime.now());
        if (durationSeconds != null) {
            video.setDurationSeconds(durationSeconds);
        }

        ExerciseVideo savedVideo = videoRepository.save(video);

        AnalysisTask task = taskService.createQueuedTask(savedVideo.getId());
        dispatchAnalysisTask(savedVideo, task);

        return savedVideo;
    }

    public void dispatchAnalysisTask(ExerciseVideo video, AnalysisTask task) {
        try {
            videoAnalysisProducer.sendAnalysisTask(video, task);
        } catch (Exception mqEx) {
            if (!fallbackLocalAsyncWhenMqDown) {
                throw new RuntimeException("MQ unavailable and local fallback disabled: " + mqEx.getMessage(), mqEx);
            }
            analysisFallbackDispatcher.dispatch(video.getId(), task.getId());
        }
    }

    public String analyzeVideoByPython(ExerciseVideo video) {
        String base = aiBaseUrl == null ? "" : aiBaseUrl.trim();
        String path = (aiAnalyzePath == null || aiAnalyzePath.isBlank()) ? "/analyze" : aiAnalyzePath.trim();
        if (!path.startsWith("/")) {
            path = "/" + path;
        }
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        String url = base + path;

        Map<String, Object> req = new HashMap<>();
        req.put("video_id", video.getId());
        Path tempVideoPath = null;
        String videoPath = video.getStoredFilePath();
        if (objectStorageService.isEnabled()) {
            String key = objectStorageService.keyFromStoredPath(video.getStoredFilePath());
            if (key == null) {
                throw new RuntimeException("Invalid COS storedFilePath: " + video.getStoredFilePath());
            }
            try {
                String suffix = storageService.extFromFilename(video.getOriginalFileName());
                tempVideoPath = objectStorageService.downloadToTemp(key, suffix);
                videoPath = tempVideoPath.toString();
            } catch (IOException e) {
                throw new UncheckedIOException("Download video from COS failed", e);
            }
        }
        req.put("video_path", videoPath);
        req.put("exercise_type", video.getExerciseType());

        @SuppressWarnings("unchecked")
        Map<String, Object> resp;
        try {
            resp = restTemplate.postForObject(url, req, Map.class);
        } finally {
            if (tempVideoPath != null) {
                storageService.safeDeleteFile(tempVideoPath.toString());
            }
        }

        if (resp == null) {
            throw new RuntimeException("Python service returned null response");
        }

        if (!resp.containsKey("rep_count") || !resp.containsKey("report_images") || !resp.containsKey("tips")) {
            throw new RuntimeException("Python response schema invalid");
        }

        if (objectStorageService.isEnabled()) {
            try {
                storageService.rewriteReportImagesToCos(video, resp);
            } catch (IOException e) {
                throw new UncheckedIOException("Upload report images to COS failed", e);
            }
        }

        resp.putIfAbsent("schema_version", "v1");

        try {
            return mapper.writeValueAsString(resp);
        } catch (Exception e) {
            throw new RuntimeException("Serialize Python response failed: " + e.getMessage(), e);
        }
    }

    public void analyzeVideo(Long videoId, Long taskId) {
        Optional<ExerciseVideo> videoOpt = videoRepository.findById(videoId);
        if (videoOpt.isEmpty()) {
            taskService.markFailed(taskId, "VIDEO_NOT_FOUND", "Video not found");
            throw new RuntimeException("Video not found");
        }

        ExerciseVideo video = videoOpt.get();
        if (taskService.isCancelled(taskId)) {
            video.setStatus(ExerciseVideo.VideoStatus.CANCELLED);
            video.setProcessedAt(LocalDateTime.now());
            video.setErrorCode("TASK_CANCELLED");
            video.setErrorMessage("Task cancelled by user");
            videoRepository.save(video);
            return;
        }

        try {
            String analysisResult = analyzeVideoByPython(video);

             if (taskService.isCancelled(taskId)) {
                video.setStatus(ExerciseVideo.VideoStatus.CANCELLED);
                video.setProcessedAt(LocalDateTime.now());
                video.setErrorCode("TASK_CANCELLED");
                video.setErrorMessage("Task cancelled by user");
                videoRepository.save(video);
                return;
            }

            analysisResult = enrichWithLLMInsight(video, analysisResult);

            video.setAnalysisResult(analysisResult);
            video.setStatus(ExerciseVideo.VideoStatus.COMPLETED);
            video.setProcessedAt(LocalDateTime.now());
            video.setErrorCode(null);
            video.setErrorMessage(null);
            video.setAnalysisSchemaVersion("v1");
            videoRepository.save(video);

            taskService.markCompleted(taskId);

            refreshUserMemoryAsync(video);
        } catch (Exception e) {
            if (taskService.isCancelled(taskId)) {
                video.setStatus(ExerciseVideo.VideoStatus.CANCELLED);
                video.setProcessedAt(LocalDateTime.now());
                video.setErrorCode("TASK_CANCELLED");
                video.setErrorMessage("Task cancelled by user");
                videoRepository.save(video);
                return;
            }
            video.setStatus(ExerciseVideo.VideoStatus.FAILED);
            video.setProcessedAt(LocalDateTime.now());
            video.setErrorCode("PYTHON_ANALYZE_ERROR");
            video.setErrorMessage(e.getMessage());
            videoRepository.save(video);

            taskService.markFailed(taskId, "PYTHON_ANALYZE_ERROR", e.getMessage());
        }
    }

    public void retryVideoAnalysis(ExerciseVideo video) {
        video.setStatus(ExerciseVideo.VideoStatus.UPLOADED);
        video.setAnalysisResult(null);
        video.setProcessedAt(null);
        video.setErrorCode(null);
        video.setErrorMessage(null);
        videoRepository.save(video);

        AnalysisTask task = taskService.createQueuedTask(video.getId());
        dispatchAnalysisTask(video, task);
    }

    @Transactional
    public void deleteVideoCascade(ExerciseVideo video) {
        storageService.deleteVideoSource(video.getStoredFilePath());

        List<Path> reportImagePaths = storageService.resolveReportImagePaths(video.getAnalysisResult());
        Set<Path> parentDirs = new HashSet<>();
        for (Path path : reportImagePaths) {
            storageService.safeDeleteFile(path.toString());
            Path parent = path.getParent();
            if (parent != null) {
                parentDirs.add(parent);
            }
        }
        storageService.pruneEmptyDirs(parentDirs);
        storageService.deleteReportImageKeys(video.getAnalysisResult());

        taskService.deleteByVideoId(video.getId());
        videoRepository.delete(video);
    }

    private String enrichWithLLMInsight(ExerciseVideo video, String analysisJson) {
        if (llmInsightService == null) {
            return analysisJson;
        }
        try {
            Map<String, Object> analysis = mapper.readValue(analysisJson, new TypeReference<>() {});
            Map<String, Object> score = trainingInsightService.calculateScore(video.getExerciseType(), analysis);

            Map<String, Object> llmInput = new LinkedHashMap<>();
            llmInput.put("exerciseType", video.getExerciseType());
            llmInput.put("finalScore", score.get("finalScore"));
            llmInput.put("level", score.get("level"));
            llmInput.put("formScore", score.get("formScore"));
            llmInput.put("repCount", score.get("repCount"));
            llmInput.put("avgMinAngle", score.get("avgMinAngle"));
            llmInput.put("targetAngle", score.get("targetAngle"));
            llmInput.put("rhythmScore", score.get("rhythmScore"));
            llmInput.put("symmetryScore", score.get("symmetryScore"));
            llmInput.put("tips", analysis.get("tips"));

            Map<String, Object> llmResult = llmInsightService.generateInsight(llmInput);
            if (llmResult != null) {
                if (llmResult.containsKey("overallFeedback")) {
                    analysis.put("overall_feedback", llmResult.get("overallFeedback"));
                }
                if (llmResult.containsKey("suggestions")) {
                    analysis.put("suggestions", llmResult.get("suggestions"));
                }
                if (llmResult.containsKey("repTipsCn")) {
                    mergeRepTipsCn(analysis.get("tips"), llmResult.get("repTipsCn"));
                }
                analysis.put("score_breakdown", score);
                return mapper.writeValueAsString(analysis);
            }
        } catch (Exception e) {
            log.warn("LLM enrichment failed: {}", e.getMessage());
        }
        return analysisJson;
    }

    @SuppressWarnings("unchecked")
    private void mergeRepTipsCn(Object tipsObj, Object repTipsCnObj) {
        if (!(tipsObj instanceof List<?> tips) || !(repTipsCnObj instanceof List<?> cnList)) {
            return;
        }
        int n = Math.min(tips.size(), cnList.size());
        for (int i = 0; i < n; i++) {
            Object tipItem = tips.get(i);
            Object cnText = cnList.get(i);
            if (tipItem instanceof Map<?, ?> tipMap && cnText instanceof String cnStr && !cnStr.isBlank()) {
                ((Map<String, Object>) tipMap).put("tip", cnStr);
            }
        }
    }

    private String normalizeExerciseType(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("exerciseType is required");
        }
        String upper = raw.trim().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
        if ("BENCHPRESS".equals(upper)) {
            upper = "BENCH_PRESS";
        } else if ("DEAD_LIFT".equals(upper)) {
            upper = "DEADLIFT";
        } else if ("SHOULDER_PRESS".equals(upper) || "DUMBBELL_PRESS".equals(upper)
                || "DUMBBELL_OVERHEAD_PRESS".equals(upper)) {
            upper = "DUMBBELL_SHOULDER_PRESS";
        } else if ("LATERAL_RAISE".equals(upper) || "SIDE_RAISE".equals(upper)
                || "DUMBBELL_SIDE_RAISE".equals(upper)) {
            upper = "DUMBBELL_LATERAL_RAISE";
        } else if ("BICEP_CURL".equals(upper) || "BICEPS_CURL".equals(upper)
                || "DUMBBELL_CURL".equals(upper)) {
            upper = "DUMBBELL_BICEP_CURL";
        } else if ("PULLUP".equals(upper) || "CHINUP".equals(upper) || "CHIN_UP".equals(upper)) {
            upper = "PULL_UP";
        }
        if (!SUPPORTED_EXERCISE_TYPES.contains(upper)) {
            throw new IllegalArgumentException("Unsupported exerciseType: " + raw);
        }
        return upper;
    }

    private void refreshUserMemoryAsync(ExerciseVideo video) {
        try {
            if (video.getUser() != null) {
                List<ExerciseVideo> userVideos = videoRepository.findByUser(video.getUser());
                userMemoryRefreshService.refreshUserMemory(video.getUser().getId(), userVideos);
            }
        } catch (Exception e) {
            log.warn("User memory refresh failed (non-blocking): {}", e.getMessage());
        }
    }
}
