package com.example.aisport.task;

import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class AnalysisTaskService {

    private final AnalysisTaskRepository repository;

    public AnalysisTaskService(AnalysisTaskRepository repository) {
        this.repository = repository;
    }

    public AnalysisTask createQueuedTask(Long videoId) {
        int attempt = repository.findTopByVideoIdOrderByIdDesc(videoId)
                .map(t -> t.getAttempt() + 1)
                .orElse(1);

        AnalysisTask task = new AnalysisTask();
        task.setVideoId(videoId);
        task.setAttempt(attempt);
        task.setStatus(AnalysisTask.TaskStatus.QUEUED);
        task.setQueuedAt(LocalDateTime.now());
        task.setCorrelationId("video-" + videoId + "-attempt-" + attempt);

        return repository.save(task);
    }

    public void markProcessing(Long taskId) {
        markProcessingIfQueued(taskId);
    }

    public boolean markProcessingIfQueued(Long taskId) {
        return repository.findById(taskId).map(task -> {
            if (task.getStatus() != AnalysisTask.TaskStatus.QUEUED) {
                return false;
            }
            task.setStatus(AnalysisTask.TaskStatus.PROCESSING);
            task.setStartedAt(LocalDateTime.now());
            repository.save(task);
            return true;
        }).orElse(false);
    }

    public void markCompleted(Long taskId) {
        repository.findById(taskId).ifPresent(task -> {
            if (task.getStatus() == AnalysisTask.TaskStatus.CANCELLED) {
                return;
            }
            task.setStatus(AnalysisTask.TaskStatus.COMPLETED);
            task.setFinishedAt(LocalDateTime.now());
            repository.save(task);
        });
    }

    public void markFailed(Long taskId, String code, String message) {
        repository.findById(taskId).ifPresent(task -> {
            if (task.getStatus() == AnalysisTask.TaskStatus.CANCELLED) {
                return;
            }
            task.setStatus(AnalysisTask.TaskStatus.FAILED);
            task.setErrorCode(code);
            task.setErrorMessage(message);
            task.setFinishedAt(LocalDateTime.now());
            repository.save(task);
        });
    }

    public boolean markCancelled(Long taskId, String code, String message) {
        return repository.findById(taskId).map(task -> {
            if (task.getStatus() == AnalysisTask.TaskStatus.COMPLETED || task.getStatus() == AnalysisTask.TaskStatus.FAILED) {
                return false;
            }
            task.setStatus(AnalysisTask.TaskStatus.CANCELLED);
            task.setErrorCode(code);
            task.setErrorMessage(message);
            task.setFinishedAt(LocalDateTime.now());
            repository.save(task);
            return true;
        }).orElse(false);
    }

    public boolean isCancelled(Long taskId) {
        return repository.findById(taskId)
                .map(t -> t.getStatus() == AnalysisTask.TaskStatus.CANCELLED)
                .orElse(false);
    }

    public Optional<AnalysisTask> findLatestByVideoId(Long videoId) {
        return repository.findTopByVideoIdOrderByIdDesc(videoId);
    }

    public List<AnalysisTask> listByVideoId(Long videoId) {
        return repository.findByVideoIdOrderByIdDesc(videoId);
    }

    public Optional<AnalysisTask> findById(Long id) {
        return repository.findById(id);
    }

    public void deleteByVideoId(Long videoId) {
        repository.deleteByVideoId(videoId);
    }

    public Map<String, Object> summarizeForVideoIds(List<Long> videoIds) {
        List<Long> e2eMs = new ArrayList<>();
        int completed = 0;
        int failed = 0;
        int processing = 0;
        int cancelled = 0;

        for (Long videoId : videoIds) {
            Optional<AnalysisTask> latest = repository.findTopByVideoIdOrderByIdDesc(videoId);
            if (latest.isEmpty()) {
                continue;
            }
            AnalysisTask t = latest.get();
            switch (t.getStatus()) {
                case COMPLETED -> completed++;
                case FAILED -> failed++;
                case CANCELLED -> cancelled++;
                case PROCESSING, QUEUED -> processing++;
            }

            if (t.getQueuedAt() != null && t.getFinishedAt() != null) {
                e2eMs.add(Duration.between(t.getQueuedAt(), t.getFinishedAt()).toMillis());
            }
        }

        e2eMs.sort(Long::compareTo);
        Double avg = e2eMs.isEmpty() ? null : e2eMs.stream().mapToLong(Long::longValue).average().orElse(0.0);
        Long p95 = null;
        if (!e2eMs.isEmpty()) {
            int idx = (int) Math.ceil(e2eMs.size() * 0.95) - 1;
            idx = Math.max(0, Math.min(idx, e2eMs.size() - 1));
            p95 = e2eMs.get(idx);
        }
        int total = completed + failed + processing + cancelled;
        Double successRate = total == 0 ? null : (completed * 1.0 / total);

        Map<String, Object> out = new HashMap<>();
        out.put("sampleSize", total);
        out.put("completed", completed);
        out.put("failed", failed);
        out.put("processing", processing);
        out.put("cancelled", cancelled);
        out.put("avgEndToEndMs", avg == null ? null : Math.round(avg));
        out.put("p95EndToEndMs", p95);
        out.put("successRate", successRate == null ? null : Math.round(successRate * 10000.0) / 10000.0);
        out.put("targetLt20sPass", avg != null && avg < 20000);
        out.put("computedAt", LocalDateTime.now());
        return out;
    }
}
