package com.example.aisport.memory;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "user_memories")
public class UserMemory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, unique = true)
    private Long userId;

    @Column(name = "username", nullable = false)
    private String username;

    @Column(name = "profile_json", columnDefinition = "LONGTEXT")
    private String profileJson;

    @Column(name = "total_sessions")
    private int totalSessions;

    @Column(name = "avg_score")
    private Double avgScore;

    @Column(name = "score_trend")
    private String scoreTrend;

    @Column(name = "weak_exercise_types", length = 512)
    private String weakExerciseTypes;

    @Column(name = "common_mistakes", columnDefinition = "TEXT")
    private String commonMistakes;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt = LocalDateTime.now();

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public String getProfileJson() { return profileJson; }
    public void setProfileJson(String profileJson) { this.profileJson = profileJson; }
    public int getTotalSessions() { return totalSessions; }
    public void setTotalSessions(int totalSessions) { this.totalSessions = totalSessions; }
    public Double getAvgScore() { return avgScore; }
    public void setAvgScore(Double avgScore) { this.avgScore = avgScore; }
    public String getScoreTrend() { return scoreTrend; }
    public void setScoreTrend(String scoreTrend) { this.scoreTrend = scoreTrend; }
    public String getWeakExerciseTypes() { return weakExerciseTypes; }
    public void setWeakExerciseTypes(String weakExerciseTypes) { this.weakExerciseTypes = weakExerciseTypes; }
    public String getCommonMistakes() { return commonMistakes; }
    public void setCommonMistakes(String commonMistakes) { this.commonMistakes = commonMistakes; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
