package com.example.aisport.service.mq;

import com.example.aisport.entity.ExerciseVideo;
import com.example.aisport.repository.ExerciseVideoRepository;
import com.example.aisport.service.VideoService;
import com.example.aisport.task.AnalysisTaskService;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.context.annotation.Lazy;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Service;

import java.util.Map;

@Service
@Lazy(false)
public class VideoAnalysisConsumer {

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
        Long videoId = ((Number) message.get("videoId")).longValue();
        Long taskId = ((Number) message.get("taskId")).longValue();

        try {
            if (!taskService.markProcessingIfQueued(taskId)) {
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
