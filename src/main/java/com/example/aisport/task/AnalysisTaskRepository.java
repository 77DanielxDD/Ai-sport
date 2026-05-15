package com.example.aisport.task;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AnalysisTaskRepository extends JpaRepository<AnalysisTask, Long> {
    Optional<AnalysisTask> findTopByVideoIdOrderByIdDesc(Long videoId);
    List<AnalysisTask> findByVideoIdOrderByIdDesc(Long videoId);
    void deleteByVideoId(Long videoId);
    List<AnalysisTask> findTop100ByOrderByIdDesc();
}
