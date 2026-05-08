package com.example.aisport.service;

import com.example.aisport.entity.ExerciseVideo;
import com.example.aisport.repository.ExerciseVideoRepository;
import com.example.aisport.task.AnalysisTaskService;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

@Service
public class AnalysisFallbackDispatcher {

    private final ExerciseVideoRepository videoRepository;
    private final AnalysisTaskService taskService;
    private final VideoService videoService;

    public AnalysisFallbackDispatcher(ExerciseVideoRepository videoRepository,
                                      AnalysisTaskService taskService,
                                      VideoService videoService) {
        this.videoRepository = videoRepository;
        this.taskService = taskService;
        this.videoService = videoService;
    }

    @Async("analysisFallbackExecutor")
    public void dispatch(Long videoId, Long taskId) {
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
            taskService.markFailed(taskId, "FALLBACK_DISPATCH_ERROR", e.getMessage());
            videoRepository.findById(videoId).ifPresent(video -> {
                video.setStatus(ExerciseVideo.VideoStatus.FAILED);
                video.setErrorCode("FALLBACK_DISPATCH_ERROR");
                video.setErrorMessage(e.getMessage());
                videoRepository.save(video);
            });
        }
    }

    @Async("analysisFallbackExecutor")
    public void dispatchIfStillQueued(Long videoId, Long taskId, long delayMs) {
        try {
            Thread.sleep(Math.max(0L, delayMs));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }
        dispatch(videoId, taskId);
    }
}
