package com.example.aisport.controller;

import com.example.aisport.entity.ExerciseVideo;
import com.example.aisport.entity.User;
import com.example.aisport.repository.AnalysisResultRepository;
import com.example.aisport.repository.ExerciseVideoRepository;
import com.example.aisport.repository.UserRepository;
import com.example.aisport.task.AnalysisTask;
import com.example.aisport.task.AnalysisTaskRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class VideoControllerApiFlowTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private ExerciseVideoRepository videoRepository;
    @Autowired
    private AnalysisTaskRepository taskRepository;
    @Autowired
    private AnalysisResultRepository analysisResultRepository;

    @BeforeEach
    void setUp() throws Exception {
        analysisResultRepository.deleteAll();
        taskRepository.deleteAll();
        videoRepository.deleteAll();
        userRepository.deleteAll();
        Files.createDirectories(Path.of("target/test-uploaded-videos"));
    }

    @Test
    void me_shouldUseRealJwtAuth() throws Exception {
        String token = registerAndGetToken("tester_me", "123456");

        mockMvc.perform(get("/api/users/me")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("tester_me"))
                .andExpect(jsonPath("$.role").value("USER"))
                .andExpect(jsonPath("$.enabled").value(true));
    }

    @Test
    void upload_shouldUseRealServiceAndPersistVideo() throws Exception {
        String token = registerAndGetToken("tester_upload", "123456");
        MockMultipartFile file = new MockMultipartFile("file", "demo.mp4", "video/mp4", "fake_video".getBytes());

        MvcResult result = mockMvc.perform(multipart("/api/videos/upload")
                        .file(file)
                        .param("exerciseType", "PUSHUP")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.videoId").isNumber())
                .andExpect(jsonPath("$.taskId").isNumber())
                .andExpect(jsonPath("$.status").value("UPLOADED"))
                .andReturn();

        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        long videoId = body.get("videoId").asLong();

        ExerciseVideo saved = videoRepository.findById(videoId).orElseThrow();
        assertThat(saved.getExerciseType()).isEqualTo("PUSHUP");
        assertThat(saved.getStoredFilePath()).isNotBlank();
        assertThat(saved.getStatus()).isIn(
                ExerciseVideo.VideoStatus.UPLOADED,
                ExerciseVideo.VideoStatus.PROCESSING,
                ExerciseVideo.VideoStatus.FAILED
        );
    }

    @Test
    void analysisCompleted_shouldReturnPayloadWithoutMocks() throws Exception {
        String username = "tester_analysis";
        String token = registerAndGetToken(username, "123456");
        User owner = userRepository.findByUsername(username).orElseThrow();

        ExerciseVideo video = new ExerciseVideo();
        video.setUser(owner);
        video.setOriginalFileName("a.mp4");
        video.setStoredFilePath("target/test-uploaded-videos/a.mp4");
        video.setExerciseType("PUSHUP");
        video.setStatus(ExerciseVideo.VideoStatus.COMPLETED);
        video.setUploadedAt(LocalDateTime.now());
        video.setProcessedAt(LocalDateTime.now());
        video.setAnalysisResult("""
                {"exercise_type":"PUSHUP","rep_count":2,"report_images":["/media/999/rep_01.png"],"tips":[{"rep_index":1,"min_angle":88.2,"tip":"ok"}],"processing_time_ms":1234}
                """);
        ExerciseVideo saved = videoRepository.save(video);

        mockMvc.perform(get("/api/videos/{id}/analysis", saved.getId())
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.videoId").value(saved.getId()))
                .andExpect(jsonPath("$.analysis.rep_count").value(2))
                .andExpect(jsonPath("$.analysis.tips[0].rep_index").value(1))
                .andExpect(jsonPath("$.analysis.trainingScore").exists());
    }

    @Test
    void cancel_shouldUpdateVideoAndTaskStateWithRealRepo() throws Exception {
        String username = "tester_cancel";
        String token = registerAndGetToken(username, "123456");
        User owner = userRepository.findByUsername(username).orElseThrow();

        ExerciseVideo video = new ExerciseVideo();
        video.setUser(owner);
        video.setOriginalFileName("b.mp4");
        video.setStoredFilePath("target/test-uploaded-videos/b.mp4");
        video.setExerciseType("PUSHUP");
        video.setStatus(ExerciseVideo.VideoStatus.PROCESSING);
        video.setUploadedAt(LocalDateTime.now());
        ExerciseVideo savedVideo = videoRepository.save(video);

        AnalysisTask task = new AnalysisTask();
        task.setVideoId(savedVideo.getId());
        task.setStatus(AnalysisTask.TaskStatus.PROCESSING);
        task.setAttempt(1);
        task.setQueuedAt(LocalDateTime.now().minusSeconds(5));
        task.setStartedAt(LocalDateTime.now().minusSeconds(4));
        taskRepository.save(task);

        mockMvc.perform(post("/api/videos/{id}/cancel", savedVideo.getId())
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.videoId").value(savedVideo.getId()))
                .andExpect(jsonPath("$.status").value("CANCELLED"));

        ExerciseVideo reloadedVideo = videoRepository.findById(savedVideo.getId()).orElseThrow();
        AnalysisTask reloadedTask = taskRepository.findTopByVideoIdOrderByIdDesc(savedVideo.getId()).orElseThrow();
        assertThat(reloadedVideo.getStatus()).isEqualTo(ExerciseVideo.VideoStatus.CANCELLED);
        assertThat(reloadedTask.getStatus()).isEqualTo(AnalysisTask.TaskStatus.CANCELLED);
    }

    @Test
    void delete_shouldCascadeDeleteWithRealRepo() throws Exception {
        String username = "tester_delete";
        String token = registerAndGetToken(username, "123456");
        User owner = userRepository.findByUsername(username).orElseThrow();

        ExerciseVideo video = new ExerciseVideo();
        video.setUser(owner);
        video.setOriginalFileName("c.mp4");
        video.setStoredFilePath("target/test-uploaded-videos/c.mp4");
        video.setExerciseType("PUSHUP");
        video.setStatus(ExerciseVideo.VideoStatus.FAILED);
        video.setUploadedAt(LocalDateTime.now());
        ExerciseVideo savedVideo = videoRepository.save(video);

        AnalysisTask task = new AnalysisTask();
        task.setVideoId(savedVideo.getId());
        task.setStatus(AnalysisTask.TaskStatus.FAILED);
        task.setAttempt(1);
        task.setQueuedAt(LocalDateTime.now().minusSeconds(3));
        task.setFinishedAt(LocalDateTime.now().minusSeconds(1));
        taskRepository.save(task);

        mockMvc.perform(delete("/api/videos/{id}", savedVideo.getId())
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.videoId").value(savedVideo.getId()))
                .andExpect(jsonPath("$.message").value("Video deleted"));

        assertThat(videoRepository.findById(savedVideo.getId())).isEmpty();
        assertThat(taskRepository.findTopByVideoIdOrderByIdDesc(savedVideo.getId())).isEmpty();
    }

    private String registerAndGetToken(String username, String password) throws Exception {
        String payload = objectMapper.writeValueAsString(Map.of(
                "username", username,
                "password", password,
                "email", username + "@example.com"
        ));
        MvcResult result = mockMvc.perform(post("/api/users/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        return body.get("token").asText();
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }
}
