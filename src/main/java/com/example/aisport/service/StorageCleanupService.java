package com.example.aisport.service;

import com.example.aisport.entity.ExerciseVideo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class StorageCleanupService {

    private static final Logger log = LoggerFactory.getLogger(StorageCleanupService.class);

    private final VideoService videoService;

    @Value("${app.cleanup.enabled:false}")
    private boolean cleanupEnabled;

    @Value("${app.cleanup.retention-days:30}")
    private int retentionDays;

    @Value("${app.cleanup.cron:0 30 3 * * *}")
    private String cronExpr;

    public StorageCleanupService(VideoService videoService) {
        this.videoService = videoService;
    }

    @Scheduled(cron = "${app.cleanup.cron:0 30 3 * * *}")
    public void scheduledCleanup() {
        Map<String, Object> result = runCleanupNow();
        log.info("storage cleanup finished: {}", result);
    }

    public synchronized Map<String, Object> runCleanupNow() {
        Map<String, Object> result = new HashMap<>();
        result.put("enabled", cleanupEnabled);
        result.put("retentionDays", retentionDays);
        result.put("cron", cronExpr);
        result.put("ranAt", LocalDateTime.now());

        if (!cleanupEnabled) {
            result.put("message", "cleanup disabled");
            result.put("deletedVideos", 0);
            result.put("failedVideos", 0);
            return result;
        }

        if (retentionDays <= 0) {
            result.put("message", "invalid retentionDays");
            result.put("deletedVideos", 0);
            result.put("failedVideos", 0);
            return result;
        }

        LocalDateTime cutoff = LocalDateTime.now().minusDays(retentionDays);
        List<ExerciseVideo> expired = videoService.findUploadedBefore(cutoff);

        int deleted = 0;
        int failed = 0;
        for (ExerciseVideo video : expired) {
            try {
                videoService.deleteVideoCascade(video);
                deleted++;
            } catch (Exception ex) {
                failed++;
                log.warn("cleanup failed for videoId={}: {}", video.getId(), ex.getMessage());
            }
        }

        result.put("cutoff", cutoff);
        result.put("candidates", expired.size());
        result.put("deletedVideos", deleted);
        result.put("failedVideos", failed);
        result.put("message", "cleanup finished");
        return result;
    }
}
