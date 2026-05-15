package com.example.aisport.controller;

import com.example.aisport.entity.ExerciseVideo;
import com.example.aisport.entity.User;
import com.example.aisport.exception.GlobalExceptionHandler;
import com.example.aisport.repository.ExerciseVideoRepository;
import com.example.aisport.service.TrainingInsightService;
import com.example.aisport.service.UserService;
import com.example.aisport.service.VideoService;
import com.example.aisport.task.AnalysisTask;
import com.example.aisport.task.AnalysisTaskService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ExtendWith(MockitoExtension.class)
class VideoControllerApiFlowTest {

    @Mock
    private VideoService videoService;
    @Mock
    private UserService userService;
    @Mock
    private ExerciseVideoRepository videoRepository;
    @Mock
    private AnalysisTaskService taskService;
    @Mock
    private TrainingInsightService trainingInsightService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        VideoController controller = new VideoController(videoService, userService, videoRepository, taskService, trainingInsightService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    private static User mockUser(String username) {
        User u = new User();
        u.setId(1L);
        u.setUsername(username);
        return u;
    }

    private static ExerciseVideo mockVideo(Long id, String owner, ExerciseVideo.VideoStatus status) {
        ExerciseVideo v = new ExerciseVideo();
        v.setId(id);
        v.setStatus(status);
        v.setExerciseType("PUSHUP");
        User u = new User();
        u.setId(1L);
        u.setUsername(owner);
        v.setUser(u);
        return v;
    }

    @Test
    void upload_shouldReturnVideoAndTask() throws Exception {
        User user = mockUser("tester");
        when(userService.findByUsername("tester")).thenReturn(Optional.of(user));

        ExerciseVideo saved = mockVideo(101L, "tester", ExerciseVideo.VideoStatus.UPLOADED);
        when(videoService.saveVideo(any(), eq(user), eq("PUSHUP"))).thenReturn(saved);
        AnalysisTask t = new AnalysisTask();
        t.setId(501L);
        when(taskService.findLatestByVideoId(101L)).thenReturn(Optional.of(t));

        MockMultipartFile file = new MockMultipartFile("file", "a.mp4", "video/mp4", "x".getBytes());

        mockMvc.perform(multipart("/api/videos/upload")
                        .file(file)
                        .param("exerciseType", "PUSHUP")
                        .principal(() -> "tester"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.videoId").value(101))
                .andExpect(jsonPath("$.taskId").value(501))
                .andExpect(jsonPath("$.status").value("UPLOADED"));
    }

    @Test
    void analysisPolling_shouldReturn202WhenNotReady() throws Exception {
        ExerciseVideo video = mockVideo(200L, "tester", ExerciseVideo.VideoStatus.UPLOADED);
        when(videoService.findById(200L)).thenReturn(Optional.of(video));

        mockMvc.perform(get("/api/videos/200/analysis").principal(() -> "tester"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.videoId").value(200))
                .andExpect(jsonPath("$.status").value("UPLOADED"))
                .andExpect(jsonPath("$.retryAfterMs").value(1000));
    }

    @Test
    void analysisCompleted_shouldReturn200AndAnalysisPayload() throws Exception {
        ExerciseVideo video = mockVideo(201L, "tester", ExerciseVideo.VideoStatus.COMPLETED);
        when(videoService.findById(201L)).thenReturn(Optional.of(video));
        when(videoService.getAnalysisResult(201L)).thenReturn("""
                {"rep_count":2,"report_images":["/media/reports/pushup/201/a.png"],"tips":[{"rep_index":1,"min_angle":88.2,"tip":"ok"}]}
                """);

        mockMvc.perform(get("/api/videos/201/analysis").principal(() -> "tester"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.videoId").value(201))
                .andExpect(jsonPath("$.analysis.rep_count").value(2))
                .andExpect(jsonPath("$.analysis.report_images[0]").value("/media/reports/pushup/201/a.png"))
                .andExpect(jsonPath("$.analysis.tips[0].rep_index").value(1));
    }

    @Test
    void cancel_shouldSetCancelledAndReturn200() throws Exception {
        ExerciseVideo video = mockVideo(301L, "tester", ExerciseVideo.VideoStatus.PROCESSING);
        when(videoService.findById(301L)).thenReturn(Optional.of(video));
        AnalysisTask task = new AnalysisTask();
        task.setId(9001L);
        task.setStatus(AnalysisTask.TaskStatus.PROCESSING);
        when(taskService.findLatestByVideoId(301L)).thenReturn(Optional.of(task));
        when(taskService.markCancelled(9001L, "TASK_CANCELLED", "Task cancelled by user")).thenReturn(true);
        when(videoRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        mockMvc.perform(post("/api/videos/301/cancel").principal(() -> "tester"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.videoId").value(301))
                .andExpect(jsonPath("$.taskId").value(9001))
                .andExpect(jsonPath("$.status").value("CANCELLED"));

        ArgumentCaptor<ExerciseVideo> captor = ArgumentCaptor.forClass(ExerciseVideo.class);
        verify(videoRepository, atLeastOnce()).save(captor.capture());
        ExerciseVideo lastSaved = captor.getValue();
        assertThat(lastSaved.getStatus()).isEqualTo(ExerciseVideo.VideoStatus.CANCELLED);
    }

    @Test
    void delete_shouldCallCascadeDelete() throws Exception {
        ExerciseVideo video = mockVideo(401L, "tester", ExerciseVideo.VideoStatus.FAILED);
        when(videoService.findById(401L)).thenReturn(Optional.of(video));

        mockMvc.perform(delete("/api/videos/401").principal(() -> "tester"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.videoId").value(401))
                .andExpect(jsonPath("$.message").value("Video deleted"));

        verify(videoService, times(1)).deleteVideoCascade(video);
    }
}
