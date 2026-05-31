package com.example.aisport.service;

import com.example.aisport.entity.ExerciseVideo;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

@Service
public class VideoStorageService {

    private final ObjectStorageService objectStorageService;
    private final ObjectMapper mapper = new ObjectMapper();

    private final String mediaBaseDir;
    private final String videoUploadPath;
    private Path videoStorageLocation;

    public VideoStorageService(
            ObjectStorageService objectStorageService,
            @Value("${app.media.base-dir:./uploaded-videos/output}") String mediaBaseDir,
            @Value("${video.upload.path:./uploaded-videos}") String videoUploadPath) {
        this.objectStorageService = objectStorageService;
        this.mediaBaseDir = mediaBaseDir;
        this.videoUploadPath = videoUploadPath;
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

    public Path getVideoStorageLocation() {
        return videoStorageLocation;
    }

    public Path getMediaRoot() {
        return Paths.get(mediaBaseDir).toAbsolutePath().normalize();
    }

    public String safeOriginalFilename(String originalName) {
        if (originalName == null || originalName.isBlank()) {
            return "video.mp4";
        }
        String base = Paths.get(originalName).getFileName().toString();
        if (base.isBlank()) {
            return "video.mp4";
        }
        return base.replaceAll("[\\\\/:*?\"<>|\\s]+", "_");
    }

    public String extFromFilename(String fileName) {
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

    @SuppressWarnings("unchecked")
    public void rewriteReportImagesToCos(ExerciseVideo video, Map<String, Object> resp) throws IOException {
        Object raw = resp.get("report_images");
        if (!(raw instanceof List<?> list) || list.isEmpty()) {
            return;
        }
        Path mediaRoot = getMediaRoot();
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

    public void deleteVideoSource(String storedFilePath) {
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

    public void deleteReportImageKeys(String analysisResult) {
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

    public List<Path> resolveReportImagePaths(String analysisResult) {
        if (analysisResult == null || analysisResult.isBlank()) {
            return List.of();
        }

        Path mediaRoot = getMediaRoot();
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

    public Path resolveMediaUrlToPath(String url, Path mediaRoot) {
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

    public void safeDeleteFile(String filePath) {
        if (filePath == null || filePath.isBlank()) {
            return;
        }
        try {
            Files.deleteIfExists(Paths.get(filePath));
        } catch (Exception ignored) {
        }
    }

    public void pruneEmptyDirs(Set<Path> dirs) {
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
}
