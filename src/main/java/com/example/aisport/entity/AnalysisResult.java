package com.example.aisport.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "analysis_results")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class AnalysisResult {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // 1个视频对应1条分析结果（你可按需改成 OneToMany）
    @Column(name = "video_id", nullable = false, unique = true)
    private Long videoId;

    @Column(name = "exercise_type", length = 20)
    private String exerciseType;

    @Column(name = "rep_count")
    private Integer repCount;

    @Column(name = "overall_score")
    private Double overallScore;

    @Column(name = "overall_feedback", columnDefinition = "TEXT")
    private String overallFeedback;

    // 存 JSON 字符串（最简单稳定，不折腾 MySQL JSON 类型）
    @Column(name = "rep_events_json", columnDefinition = "LONGTEXT")
    private String repEventsJson;

    @Column(name = "result_json_path", columnDefinition = "TEXT")
    private String resultJsonPath;

    @Column(name = "processing_time_ms")
    private Integer processingTimeMs;

    @Column(name = "analyzed_at")
    private LocalDateTime analyzedAt;
}
