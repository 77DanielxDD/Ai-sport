package com.example.aisport.service.mq;

import com.example.aisport.entity.ExerciseVideo;
import com.example.aisport.repository.ExerciseVideoRepository;
import com.example.aisport.service.VideoService;
import com.example.aisport.task.AnalysisTaskService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Service;

import java.util.Map;

@Service
public class VideoAnalysisConsumer {

    private static final Logger log = LoggerFactory.getLogger(VideoAnalysisConsumer.class);

    private final VideoService videoService;
    private final ExerciseVideoRepository videoRepository;
    private final AnalysisTaskService taskService;

    public VideoAnalysisConsumer(VideoService videoService,
                                 ExerciseVideoRepository videoRepository,
                                 AnalysisTaskService taskService) {
        this.videoService = videoService;
        this.videoRepository = videoRepository;
        this.taskService = taskService;
    }

    @RabbitListener(queues = "${mq.queue.video-analysis}")
    public void receiveAnalysisTask(@Payload Map<String, Object> message) {
        Object videoIdObj = message.get("videoId");
        Object taskIdObj = message.get("taskId");
        if (videoIdObj == null || taskIdObj == null) {
            log.warn("Malformed analysis message, ack and skip: {}", message);
            return;
        }
        Long videoId = ((Number) videoIdObj).longValue();
        Long taskId = ((Number) taskIdObj).longValue();
        String messageId = String.valueOf(message.getOrDefault("messageId", ""));
        String correlationId = String.valueOf(message.getOrDefault("correlationId", ""));
        int schemaVersion = message.get("schemaVersion") instanceof Number n ? n.intValue() : 1;
        int attempt = message.get("attempt") instanceof Number n2 ? n2.intValue() : 1;

        log.info("Consumed analysis message taskId={} videoId={} messageId={} correlationId={} schemaVersion={} attempt={}",
                taskId, videoId, messageId, correlationId, schemaVersion, attempt);

        try {
            // 幂等：只有 QUEUED 的任务允许进入 PROCESSING，重复消息/重复消费一律跳过。
            if (!taskService.markProcessingIfQueued(taskId)) {
                taskService.recordDuplicateSkip(taskId, "already_processed_or_terminal");
                return;
            }
            ExerciseVideo video = videoRepository.findById(videoId)
                    .orElseThrow(() -> new RuntimeException("Video not found"));

            if (taskService.isCancelled(taskId)) {
                video.setStatus(ExerciseVideo.VideoStatus.CANCELLED);
                video.setErrorCode("TASK_CANCELLED");
                video.setErrorMessage("Task cancelled by user");
                videoRepository.save(video);
                return;
            }

            video.setStatus(ExerciseVideo.VideoStatus.PROCESSING);
            videoRepository.save(video);

            videoService.analyzeVideo(videoId, taskId);
        } catch (Exception e) {
            if (taskService.isCancelled(taskId)) {
                return;
            }
            taskService.markFailed(taskId, "CONSUMER_ERROR", e.getMessage());
            videoRepository.findById(videoId).ifPresent(video -> {
                video.setStatus(ExerciseVideo.VideoStatus.FAILED);
                video.setErrorCode("CONSUMER_ERROR");
                video.setErrorMessage(e.getMessage());
                videoRepository.save(video);
            });
        }
    }
}
