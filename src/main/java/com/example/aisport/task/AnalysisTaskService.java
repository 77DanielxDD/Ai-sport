package com.example.aisport.task;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
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

    private static final Logger log = LoggerFactory.getLogger(AnalysisTaskService.class);

    private final AnalysisTaskRepository repository;
    private final TaskEventBroadcaster eventBroadcaster;
    private final Counter duplicateSkipCounter;
    private final Counter transitionCounter;

    public AnalysisTaskService(AnalysisTaskRepository repository,
                               TaskEventBroadcaster eventBroadcaster,
                               MeterRegistry meterRegistry) {
        this.repository = repository;
        this.eventBroadcaster = eventBroadcaster;
        this.duplicateSkipCounter = meterRegistry.counter("ai_sport_task_duplicate_skip_total");
        this.transitionCounter = meterRegistry.counter("ai_sport_task_status_transition_total");
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

        AnalysisTask saved = repository.save(task);
        eventBroadcaster.publish(videoId, AnalysisTask.TaskStatus.QUEUED.name());
        return saved;
    }

    public void markProcessing(Long taskId) {
        markProcessingIfQueued(taskId);
    }

    /**
     * QUEUED -> PROCESSING 的原子迁移。
     * 幂等键：taskId（任务表为唯一真相）。重复消费/重复消息第二次进来时任务已非 QUEUED，
     * 直接返回 false；乐观锁兜底并发竞争。
     */
    public boolean markProcessingIfQueued(Long taskId) {
        Optional<AnalysisTask> opt = repository.findById(taskId);
        if (opt.isEmpty()) {
            return false;
        }
        AnalysisTask task = opt.get();
        if (task.getStatus() != AnalysisTask.TaskStatus.QUEUED) {
            return false;
        }
        try {
            task.setStatus(AnalysisTask.TaskStatus.PROCESSING);
            task.setStartedAt(LocalDateTime.now());
            repository.save(task);
            transitionCounter.increment();
            eventBroadcaster.publish(task.getVideoId(), AnalysisTask.TaskStatus.PROCESSING.name());
            return true;
        } catch (ObjectOptimisticLockingFailureException e) {
            log.warn("Task {} transition QUEUED->PROCESSING lost optimistic lock, treated as duplicate", taskId);
            return false;
        }
    }

    private static boolean isTerminalState(AnalysisTask.TaskStatus status) {
        return status == AnalysisTask.TaskStatus.COMPLETED
                || status == AnalysisTask.TaskStatus.FAILED
                || status == AnalysisTask.TaskStatus.CANCELLED;
    }

    public void markCompleted(Long taskId) {
        repository.findById(taskId).ifPresent(task -> {
            if (isTerminalState(task.getStatus())) {
                duplicateSkipCounter.increment();
                return;
            }
            task.setStatus(AnalysisTask.TaskStatus.COMPLETED);
            task.setFinishedAt(LocalDateTime.now());
            repository.save(task);
            transitionCounter.increment();
            eventBroadcaster.publish(task.getVideoId(), AnalysisTask.TaskStatus.COMPLETED.name());
        });
    }

    public void markFailed(Long taskId, String code, String message) {
        repository.findById(taskId).ifPresent(task -> {
            if (isTerminalState(task.getStatus())) {
                duplicateSkipCounter.increment();
                return;
            }
            task.setStatus(AnalysisTask.TaskStatus.FAILED);
            task.setErrorCode(code);
            task.setErrorMessage(message);
            task.setFinishedAt(LocalDateTime.now());
            repository.save(task);
            transitionCounter.increment();
            eventBroadcaster.publish(task.getVideoId(), AnalysisTask.TaskStatus.FAILED.name());
        });
    }

    public boolean markCancelled(Long taskId, String code, String message) {
        return repository.findById(taskId).map(task -> {
            if (task.getStatus() == AnalysisTask.TaskStatus.COMPLETED
                    || task.getStatus() == AnalysisTask.TaskStatus.FAILED) {
                duplicateSkipCounter.increment();
                return false;
            }
            if (task.getStatus() == AnalysisTask.TaskStatus.CANCELLED) {
                return false;
            }
            task.setStatus(AnalysisTask.TaskStatus.CANCELLED);
            task.setErrorCode(code);
            task.setErrorMessage(message);
            task.setFinishedAt(LocalDateTime.now());
            repository.save(task);
            transitionCounter.increment();
            eventBroadcaster.publish(task.getVideoId(), AnalysisTask.TaskStatus.CANCELLED.name());
            return true;
        }).orElse(false);
    }

    /** 记录一次重复消息/重复处理的跳过，供 Prometheus 观测。 */
    public void recordDuplicateSkip(Long taskId, String reason) {
        duplicateSkipCounter.increment();
        log.debug("Skipped duplicate task {} reason={}", taskId, reason);
    }

    public boolean isTerminal(Long taskId) {
        return repository.findById(taskId)
                .map(t -> t.getStatus() == AnalysisTask.TaskStatus.COMPLETED
                        || t.getStatus() == AnalysisTask.TaskStatus.FAILED
                        || t.getStatus() == AnalysisTask.TaskStatus.CANCELLED)
                .orElse(false);
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
