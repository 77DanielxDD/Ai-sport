package com.example.aisport.integration;

import com.example.aisport.task.AnalysisTask;
import com.example.aisport.task.AnalysisTaskRepository;
import com.example.aisport.task.AnalysisTaskService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DataJpaTest(properties = {
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.jpa.show-sql=false"
})
@Import(AnalysisTaskService.class)
@ActiveProfiles("dev")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class RealMySqlWhiteBoxIntegrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private AnalysisTaskService taskService;

    @Autowired
    private AnalysisTaskRepository taskRepository;

    @Test
    void mysqlConnectivityAndRequiredTablesShouldBeReady() {
        Integer one = jdbcTemplate.queryForObject("SELECT 1", Integer.class);
        assertEquals(1, one);

        String database = jdbcTemplate.queryForObject("SELECT DATABASE()", String.class);
        assertEquals("ai_sport", database);

        Integer tableCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables " +
                        "WHERE table_schema = DATABASE() " +
                        "AND table_name IN ('users','exercise_videos','analysis_tasks','analysis_results')",
                Integer.class
        );
        assertEquals(4, tableCount);
    }

    @Test
    void taskStateMachineShouldRunOnRealMySqlWithoutMock() {
        long videoId = (System.currentTimeMillis() % 1_000_000_000L) + Math.abs(UUID.randomUUID().getLeastSignificantBits() % 10_000);

        AnalysisTask queued = taskService.createQueuedTask(videoId);
        assertNotNull(queued.getId());
        assertEquals(AnalysisTask.TaskStatus.QUEUED, queued.getStatus());

        boolean movedToProcessing = taskService.markProcessingIfQueued(queued.getId());
        assertTrue(movedToProcessing);

        AnalysisTask processing = taskRepository.findById(queued.getId()).orElseThrow();
        assertEquals(AnalysisTask.TaskStatus.PROCESSING, processing.getStatus());
        assertNotNull(processing.getStartedAt());

        taskService.markCompleted(queued.getId());
        AnalysisTask completed = taskRepository.findById(queued.getId()).orElseThrow();
        assertEquals(AnalysisTask.TaskStatus.COMPLETED, completed.getStatus());
        assertNotNull(completed.getFinishedAt());

        taskRepository.deleteById(queued.getId());
    }
}
