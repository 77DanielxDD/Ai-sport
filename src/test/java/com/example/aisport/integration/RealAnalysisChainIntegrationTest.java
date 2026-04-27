package com.example.aisport.integration;

import com.example.aisport.entity.ExerciseVideo;
import com.example.aisport.entity.User;
import com.example.aisport.repository.AnalysisResultRepository;
import com.example.aisport.repository.ExerciseVideoRepository;
import com.example.aisport.repository.UserRepository;
import com.example.aisport.service.VideoService;
import com.example.aisport.task.AnalysisTask;
import com.example.aisport.task.AnalysisTaskRepository;
import com.example.aisport.task.AnalysisTaskService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(properties = {
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.jpa.show-sql=false",
        "spring.rabbitmq.listener.simple.auto-startup=false"
})
@ActiveProfiles("dev")
@Transactional
class RealAnalysisChainIntegrationTest {

    @Autowired
    private VideoService videoService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ExerciseVideoRepository videoRepository;

    @Autowired
    private AnalysisTaskService taskService;

    @Autowired
    private AnalysisTaskRepository taskRepository;

    @Autowired
    private AnalysisResultRepository analysisResultRepository;

    @Test
    void shouldRunRealJavaToPythonToMySqlAnalysisChainWithoutMock() throws Exception {
        String samplePath = System.getProperty(
                "analysis.sample.video.path",
                "C:\\Users\\Administrator\\Videos\\Captures\\VALORANT   2026-03-11 04-17-43.mp4"
        );
        Path videoPath = Path.of(samplePath);
        assertTrue(Files.exists(videoPath), "Sample video not found: " + samplePath);

        String seed = UUID.randomUUID().toString().replace("-", "").substring(0, 10);

        User user = new User();
        user.setUsername("analysis_user_" + seed);
        user.setPassword("noop-password");
        user.setEmail(seed + "@local.test");
        user.setRole(User.UserRole.USER);
        user.setEnabled(true);
        user.setCreatedAt(LocalDateTime.now());
        user = userRepository.save(user);

        ExerciseVideo video = new ExerciseVideo();
        video.setUser(user);
        video.setOriginalFileName(videoPath.getFileName().toString());
        video.setStoredFilePath(videoPath.toString());
        video.setExerciseType("SQUAT");
        video.setStatus(ExerciseVideo.VideoStatus.UPLOADED);
        video.setUploadedAt(LocalDateTime.now());
        video.setFileSizeMb(Files.size(videoPath) / (1024.0 * 1024.0));
        video = videoRepository.save(video);

        AnalysisTask task = taskService.createQueuedTask(video.getId());
        taskService.markProcessingIfQueued(task.getId());

        videoService.analyzeVideo(video.getId(), task.getId());

        ExerciseVideo updated = videoRepository.findById(video.getId()).orElseThrow();
        assertEquals(ExerciseVideo.VideoStatus.COMPLETED, updated.getStatus());
        assertNotNull(updated.getProcessedAt());
        assertNotNull(updated.getAnalysisResult());
        assertTrue(updated.getAnalysisResult().contains("\"rep_count\""));
        assertTrue(updated.getAnalysisResult().contains("\"report_images\""));
        assertTrue(updated.getAnalysisResult().contains("\"tips\""));

        AnalysisTask doneTask = taskRepository.findById(task.getId()).orElseThrow();
        assertEquals(AnalysisTask.TaskStatus.COMPLETED, doneTask.getStatus());

        var analysisResult = analysisResultRepository.findByVideoId(video.getId()).orElseThrow();
        assertEquals(video.getId(), analysisResult.getVideoId());
        assertTrue((analysisResult.getRepCount() == null ? 0 : analysisResult.getRepCount()) > 0);

    }
}
