package com.example.aisport.service;

import com.example.aisport.task.AnalysisTask;
import com.example.aisport.task.AnalysisTaskRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class AnalysisQueueRecoveryService {
    private static final Logger log = LoggerFactory.getLogger(AnalysisQueueRecoveryService.class);

    private final AnalysisTaskRepository taskRepository;
    private final AnalysisFallbackDispatcher fallbackDispatcher;

    @Value("${app.analysis.queue-recovery.enabled:true}")
    private String enabledRaw;

    @Value("${app.analysis.queue-stuck-seconds:20}")
    private String queueStuckSecondsRaw;

    public AnalysisQueueRecoveryService(AnalysisTaskRepository taskRepository,
                                        AnalysisFallbackDispatcher fallbackDispatcher) {
        this.taskRepository = taskRepository;
        this.fallbackDispatcher = fallbackDispatcher;
    }

    @Scheduled(fixedDelay = 15000)
    public void recoverStuckQueuedTasks() {
        if (!parseBool(enabledRaw, true)) {
            return;
        }
        int queueStuckSeconds = Math.max(5, parseInt(queueStuckSecondsRaw, 20));
        LocalDateTime threshold = LocalDateTime.now().minusSeconds(queueStuckSeconds);
        List<AnalysisTask> staleQueued = taskRepository
                .findTop50ByStatusAndQueuedAtBeforeOrderByQueuedAtAsc(AnalysisTask.TaskStatus.QUEUED, threshold);

        for (AnalysisTask task : staleQueued) {
            log.warn("Recovering stale queued task: taskId={}, videoId={}, queuedAt={}",
                    task.getId(), task.getVideoId(), task.getQueuedAt());
            fallbackDispatcher.dispatch(task.getVideoId(), task.getId());
        }
    }

    private boolean parseBool(String raw, boolean defaultVal) {
        if (raw == null || raw.isBlank()) {
            return defaultVal;
        }
        String v = raw.trim().toLowerCase();
        if ("true".equals(v) || "1".equals(v) || "yes".equals(v) || "on".equals(v)) {
            return true;
        }
        if ("false".equals(v) || "0".equals(v) || "no".equals(v) || "off".equals(v)) {
            return false;
        }
        return defaultVal;
    }

    private int parseInt(String raw, int defaultVal) {
        if (raw == null || raw.isBlank()) {
            return defaultVal;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (Exception ignored) {
            return defaultVal;
        }
    }
}
