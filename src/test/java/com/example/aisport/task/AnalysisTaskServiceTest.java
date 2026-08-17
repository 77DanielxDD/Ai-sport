package com.example.aisport.task;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AnalysisTaskServiceTest {

    private AnalysisTaskService newService(AnalysisTaskRepository repo) {
        return new AnalysisTaskService(repo, mock(TaskEventBroadcaster.class), new SimpleMeterRegistry());
    }

    @Test
    void duplicateMessageProcessedOnlyOnce() {
        AnalysisTaskRepository repo = mock(AnalysisTaskRepository.class);
        AnalysisTask task = new AnalysisTask();
        task.setId(1L);
        task.setVideoId(10L);
        task.setStatus(AnalysisTask.TaskStatus.QUEUED);
        when(repo.findById(1L)).thenReturn(Optional.of(task));

        AnalysisTaskService svc = newService(repo);

        assertTrue(svc.markProcessingIfQueued(1L), "first consume should transition QUEUED->PROCESSING");
        assertFalse(svc.markProcessingIfQueued(1L), "duplicate message must be skipped");
        assertEquals(AnalysisTask.TaskStatus.PROCESSING, task.getStatus());
    }

    @Test
    void terminalTaskCannotReenterProcessing() {
        AnalysisTaskRepository repo = mock(AnalysisTaskRepository.class);
        AnalysisTask task = new AnalysisTask();
        task.setId(2L);
        task.setVideoId(10L);
        task.setStatus(AnalysisTask.TaskStatus.COMPLETED);
        when(repo.findById(2L)).thenReturn(Optional.of(task));

        AnalysisTaskService svc = newService(repo);

        assertFalse(svc.markProcessingIfQueued(2L), "terminal task must not re-enter PROCESSING");
    }

    @Test
    void completedTaskDoesNotOverrideFailure() {
        AnalysisTaskRepository repo = mock(AnalysisTaskRepository.class);
        AnalysisTask task = new AnalysisTask();
        task.setId(3L);
        task.setVideoId(10L);
        task.setStatus(AnalysisTask.TaskStatus.FAILED);
        when(repo.findById(3L)).thenReturn(Optional.of(task));

        AnalysisTaskService svc = newService(repo);
        svc.markCompleted(3L);

        assertEquals(AnalysisTask.TaskStatus.FAILED, task.getStatus(), "FAILED must not be overwritten to COMPLETED");
        verify(repo, times(0)).save(task);
    }

    @Test
    void isTerminalDetectsFinalStates() {
        AnalysisTaskRepository repo = mock(AnalysisTaskRepository.class);
        AnalysisTaskService svc = newService(repo);

        for (AnalysisTask.TaskStatus s : new AnalysisTask.TaskStatus[]{
                AnalysisTask.TaskStatus.COMPLETED, AnalysisTask.TaskStatus.FAILED, AnalysisTask.TaskStatus.CANCELLED}) {
            AnalysisTask t = new AnalysisTask();
            t.setId(9L);
            t.setStatus(s);
            when(repo.findById(9L)).thenReturn(Optional.of(t));
            assertTrue(svc.isTerminal(9L), s + " should be terminal");
        }
    }
}
