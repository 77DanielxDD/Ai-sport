package com.example.aisport.task;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * 轻量 SSE 广播器：按 videoId 维护订阅者，任务状态变化时推送。
 * 只推状态事件，不承载分析结果本体。
 */
@Component
public class TaskEventBroadcaster {

    private static final Logger log = LoggerFactory.getLogger(TaskEventBroadcaster.class);

    private final Map<Long, Set<SseEmitter>> subscribers = new ConcurrentHashMap<>();
    private final Counter pushEventCounter;
    private final Counter disconnectCounter;

    public TaskEventBroadcaster(MeterRegistry meterRegistry) {
        this.pushEventCounter = meterRegistry.counter("task_push_event_total");
        this.disconnectCounter = meterRegistry.counter("task_push_disconnect_total");
    }

    public SseEmitter subscribe(Long videoId) {
        SseEmitter emitter = new SseEmitter(180_000L);
        subscribers.computeIfAbsent(videoId, k -> new CopyOnWriteArraySet<>()).add(emitter);
        emitter.onCompletion(() -> remove(videoId, emitter));
        emitter.onTimeout(() -> remove(videoId, emitter));
        emitter.onError(e -> remove(videoId, emitter));
        log.debug("SSE subscribed videoId={} total={}", videoId, subscribers.get(videoId).size());
        return emitter;
    }

    public void publish(Long videoId, String status) {
        Set<SseEmitter> set = subscribers.get(videoId);
        if (set == null || set.isEmpty()) {
            return;
        }
        for (SseEmitter emitter : set) {
            try {
                emitter.send(SseEmitter.event().name("status").data(status));
                pushEventCounter.increment();
            } catch (IOException e) {
                emitter.completeWithError(e);
                disconnectCounter.increment();
                remove(videoId, emitter);
            }
        }
    }

    private void remove(Long videoId, SseEmitter emitter) {
        Set<SseEmitter> set = subscribers.get(videoId);
        if (set != null) {
            set.remove(emitter);
            if (set.isEmpty()) {
                subscribers.remove(videoId);
            }
            disconnectCounter.increment();
        }
    }
}
