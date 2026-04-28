package com.example.aisport.service;

import com.example.aisport.entity.AnalysisResult;
import com.example.aisport.entity.ExerciseVideo;
import com.example.aisport.entity.User;
import com.example.aisport.repository.AnalysisResultRepository;
import com.example.aisport.repository.ExerciseVideoRepository;
import com.example.aisport.service.mq.VideoAnalysisProducer;
import com.example.aisport.task.AnalysisTask;
import com.example.aisport.task.AnalysisTaskService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
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
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.Locale;

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
    private final AnalysisResultRepository analysisResultRepository;

    private final ObjectMapper mapper = new ObjectMapper();

    @Value("${ai.service.base-url:http://127.0.0.1:8000}")
    private String aiBaseUrl;

    @Value("${ai.python.analyze-path:/analyze}")
    private String aiAnalyzePath;

    @Value("${app.analysis.fallback-local-async-when-mq-down:true}")
    private boolean fallbackLocalAsyncWhenMqDown;

    @Value("${app.media.base-dir:./uploaded-videos/output}")
    private String mediaBaseDir;

    @Value("${video.upload.path:./uploaded-videos}")
    private String videoUploadPath;

    private Path videoStorageLocation;

    public VideoService(ExerciseVideoRepository videoRepository,
                        VideoAnalysisProducer videoAnalysisProducer,
                        @Lazy AnalysisFallbackDispatcher analysisFallbackDispatcher,
                        RestTemplate restTemplate,
                        AnalysisTaskService taskService,
                        ObjectStorageService objectStorageService,
                        AnalysisResultRepository analysisResultRepository) {
        this.videoRepository = videoRepository;
        this.videoAnalysisProducer = videoAnalysisProducer;
        this.analysisFallbackDispatcher = analysisFallbackDispatcher;
        this.restTemplate = restTemplate;
        this.taskService = taskService;
        this.objectStorageService = objectStorageService;
        this.analysisResultRepository = analysisResultRepository;
    }

    @PostConstruct
    public void initStorageLocation() {
        this.videoStorageLocation = Paths.get(videoUploadPath).toAbsolutePath().normalize();
        try {
            Files.createDirectories(this.videoStorageLocation);
        } catch (IOException e) {
            throw new RuntimeException("Cannot create video storage directory", e);
        }
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
        String normalizedType = normalizeExerciseType(exerciseType);
        String original = safeOriginalFilename(file.getOriginalFilename());
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
                safeDeleteFile(tmp.toString());
            }
        } else {
            Path targetLocation = this.videoStorageLocation.resolve(fileName);
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
                String suffix = extFromFilename(video.getOriginalFileName());
                tempVideoPath = objectStorageService.downloadToTemp(key, suffix);
                videoPath = tempVideoPath.toString();
            } catch (IOException e) {
                throw new UncheckedIOException("Download video from COS failed", e);
            }
        }
        req.put("video_path", videoPath);
        List<String> videoPathCandidates = buildVideoPathCandidates(videoPath);
        if (!videoPathCandidates.isEmpty()) {
            req.put("video_path_candidates", videoPathCandidates);
        }
        req.put("exercise_type", video.getExerciseType());

        @SuppressWarnings("unchecked")
        Map<String, Object> resp;
        try {
            resp = restTemplate.postForObject(url, req, Map.class);
        } finally {
            if (tempVideoPath != null) {
                safeDeleteFile(tempVideoPath.toString());
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
                rewriteReportImagesToCos(video, resp);
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

            video.setAnalysisResult(analysisResult);
            video.setStatus(ExerciseVideo.VideoStatus.COMPLETED);
            video.setProcessedAt(LocalDateTime.now());
            video.setErrorCode(null);
            video.setErrorMessage(null);
            video.setAnalysisSchemaVersion("v1");
            videoRepository.save(video);
            persistStructuredResult(video, analysisResult);

            taskService.markCompleted(taskId);
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
        analysisResultRepository.deleteByVideoId(video.getId());

        AnalysisTask task = taskService.createQueuedTask(video.getId());
        dispatchAnalysisTask(video, task);
    }

    @Transactional
    public Map<String, Object> migrateReportImagesToCos(ExerciseVideo video) throws IOException {
        if (video == null) {
            throw new IllegalArgumentException("video is required");
        }
        return Map.of(
                "videoId", video.getId(),
                "message", "Migration endpoint is available in lightweight mode"
        );
    }
    @Transactional
    public void deleteVideoCascade(ExerciseVideo video) {
        deleteVideoSource(video.getStoredFilePath());

        List<Path> reportImagePaths = resolveReportImagePaths(video.getAnalysisResult());
        Set<Path> parentDirs = new HashSet<>();
        for (Path path : reportImagePaths) {
            safeDeleteFile(path.toString());
            Path parent = path.getParent();
            if (parent != null) {
                parentDirs.add(parent);
            }
        }
        pruneEmptyDirs(parentDirs);
        deleteReportImageKeys(video.getAnalysisResult());

        taskService.deleteByVideoId(video.getId());
        analysisResultRepository.deleteByVideoId(video.getId());
        videoRepository.delete(video);
    }

    private void persistStructuredResult(ExerciseVideo video, String analysisJson) {
        try {
            Map<String, Object> root = mapper.readValue(analysisJson, new TypeReference<>() {});
            AnalysisResult entity = analysisResultRepository.findByVideoId(video.getId()).orElseGet(AnalysisResult::new);

            entity.setVideoId(video.getId());
            entity.setExerciseType(video.getExerciseType());
            entity.setRepCount(parseInt(root.get("rep_count"), parseInt(root.get("repCount"), 0)));
            entity.setOverallScore(parseDouble(root.get("overall_score"), parseDouble(root.get("score"), null)));
            entity.setOverallFeedback(firstNonBlank(
                    asString(root.get("overall_feedback")),
                    asString(root.get("overallFeedback")),
                    asString(root.get("summary"))
            ));
            entity.setRepEventsJson(writeJson(root.get("rep_events"), root.get("tips")));
            entity.setResultJsonPath("inline:exercise_videos.analysisResult");
            entity.setProcessingTimeMs(parseInt(root.get("processing_time_ms"), null));
            entity.setAnalyzedAt(LocalDateTime.now());

            analysisResultRepository.save(entity);
        } catch (Exception ex) {
            log.warn("Persist structured analysis result failed for videoId={}: {}", video.getId(), ex.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private void rewriteReportImagesToCos(ExerciseVideo video, Map<String, Object> resp) throws IOException {
        Object raw = resp.get("report_images");
        if (!(raw instanceof List<?> list) || list.isEmpty()) {
            return;
        }
        Path mediaRoot = Paths.get(mediaBaseDir).toAbsolutePath().normalize();
        List<String> newUrls = new ArrayList<>();
        List<String> imageKeys = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof String imgUrl) || imgUrl.isBlank()) {
                continue;
            }
            Path localPath = resolveMediaUrlToPath(imgUrl, mediaRoot);
            if (localPath == null || !Files.exists(localPath)) {
                continue;
            }
            String key = objectStorageService.buildReportObjectKey(video.getId(), localPath.getFileName().toString());
            String publicUrl = objectStorageService.uploadFile(localPath, key, "image/png");
            newUrls.add(publicUrl);
            imageKeys.add(key);
            safeDeleteFile(localPath.toString());
        }
        if (!newUrls.isEmpty()) {
            resp.put("report_images", newUrls);
            resp.put("report_image_keys", imageKeys);
        }
    }

    private void deleteVideoSource(String storedFilePath) {
        if (storedFilePath == null || storedFilePath.isBlank()) {
            return;
        }
        if (objectStorageService.isEnabled()) {
            String key = objectStorageService.keyFromStoredPath(storedFilePath);
            if (key != null) {
                objectStorageService.deleteObject(key);
                return;
            }
        }
        safeDeleteFile(storedFilePath);
    }

    private void deleteReportImageKeys(String analysisResult) {
        if (!objectStorageService.isEnabled() || analysisResult == null || analysisResult.isBlank()) {
            return;
        }
        try {
            Map<String, Object> root = mapper.readValue(analysisResult, new TypeReference<>() {});
            Object raw = root.get("report_image_keys");
            if (!(raw instanceof List<?> keys)) {
                return;
            }
            for (Object key : keys) {
                if (key instanceof String s && !s.isBlank()) {
                    objectStorageService.deleteObject(s);
                }
            }
        } catch (Exception ignored) {
        }
    }

    private List<Path> resolveReportImagePaths(String analysisResult) {
        if (analysisResult == null || analysisResult.isBlank()) {
            return List.of();
        }

        Path mediaRoot = Paths.get(mediaBaseDir).toAbsolutePath().normalize();
        List<Path> out = new ArrayList<>();
        try {
            Map<String, Object> root = mapper.readValue(analysisResult, new TypeReference<>() {});
            Object imagesObj = root.get("report_images");
            if (!(imagesObj instanceof List<?> images)) {
                return List.of();
            }

            for (Object raw : images) {
                if (!(raw instanceof String url) || url.isBlank()) {
                    continue;
                }
                Path resolved = resolveMediaUrlToPath(url, mediaRoot);
                if (resolved != null) {
                    out.add(resolved);
                }
            }
        } catch (Exception ignored) {
            return List.of();
        }
        return out;
    }

    private Path resolveMediaUrlToPath(String url, Path mediaRoot) {
        String marker = "/media/";
        int idx = url.indexOf(marker);
        if (idx < 0) {
            return null;
        }
        String relative = url.substring(idx + marker.length());
        if (relative.isBlank()) {
            return null;
        }
        Path candidate = mediaRoot.resolve(relative).normalize();
        if (!candidate.startsWith(mediaRoot)) {
            return null;
        }
        return candidate;
    }

    private void safeDeleteFile(String filePath) {
        if (filePath == null || filePath.isBlank()) {
            return;
        }
        try {
            Files.deleteIfExists(Paths.get(filePath));
        } catch (Exception ignored) {
        }
    }

    private void pruneEmptyDirs(Set<Path> dirs) {
        if (dirs.isEmpty()) {
            return;
        }
        List<Path> sorted = dirs.stream()
                .sorted(Comparator.comparingInt(Path::getNameCount).reversed())
                .toList();
        for (Path dir : sorted) {
            try {
                if (Files.exists(dir) && Files.isDirectory(dir)) {
                    try (var stream = Files.list(dir)) {
                        if (stream.findAny().isEmpty()) {
                            Files.deleteIfExists(dir);
                        }
                    }
                }
            } catch (Exception ignored) {
            }
        }
    }

    private String safeOriginalFilename(String originalName) {
        if (originalName == null || originalName.isBlank()) {
            return "video.mp4";
        }
        String base = Paths.get(originalName).getFileName().toString();
        if (base.isBlank()) {
            return "video.mp4";
        }
        return base.replaceAll("[\\\\/:*?\"<>|\\s]+", "_");
    }

    private String extFromFilename(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return ".mp4";
        }
        int idx = fileName.lastIndexOf('.');
        if (idx < 0 || idx == fileName.length() - 1) {
            return ".mp4";
        }
        String ext = fileName.substring(idx);
        return ext.length() > 10 ? ".mp4" : ext;
    }

    private List<String> buildVideoPathCandidates(String originalPath) {
        if (originalPath == null || originalPath.isBlank()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        out.add(originalPath);
        try {
            Path absolute = Paths.get(originalPath).toAbsolutePath().normalize();
            if (videoStorageLocation != null && absolute.startsWith(videoStorageLocation)) {
                Path relative = videoStorageLocation.relativize(absolute);
                String dockerPath = "/data/uploaded-videos/" + relative.toString().replace("\\", "/");
                if (!dockerPath.equals(originalPath)) {
                    out.add(dockerPath);
                }
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    private Integer parseInt(Object val, Integer defVal) {
        if (val == null) {
            return defVal;
        }
        try {
            return Integer.parseInt(String.valueOf(val));
        } catch (Exception ignored) {
            return defVal;
        }
    }

    private Double parseDouble(Object val, Double defVal) {
        if (val == null) {
            return defVal;
        }
        try {
            return Double.parseDouble(String.valueOf(val));
        } catch (Exception ignored) {
            return defVal;
        }
    }

    private String asString(Object val) {
        return val == null ? null : String.valueOf(val);
    }

    private String writeJson(Object primary, Object fallback) {
        Object target = primary != null ? primary : fallback;
        if (target == null) {
            return "[]";
        }
        try {
            return mapper.writeValueAsString(target);
        } catch (Exception ignored) {
            return "[]";
        }
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
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
}


