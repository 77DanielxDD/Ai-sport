package com.example.aisport.repository;

import com.example.aisport.entity.ExerciseVideo;
import com.example.aisport.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.time.LocalDateTime;

@Repository
public interface ExerciseVideoRepository extends JpaRepository<ExerciseVideo, Long> {
    // 根据用户查询视频
    List<ExerciseVideo> findByUser(User user);
    List<ExerciseVideo> findTop100ByOrderByIdDesc();
    List<ExerciseVideo> findByUploadedAtBefore(LocalDateTime cutoff);
    long countByStatus(ExerciseVideo.VideoStatus status);
}
