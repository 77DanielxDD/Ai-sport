package com.example.aisport.service;

import com.example.aisport.entity.ExerciseVideo;
import org.springframework.stereotype.Service;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.LocalDateTime;

@Service
public class VideoAnalysisService {

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 模拟AI视频分析
     * 实际项目中这里会调用Python AI服务或TensorFlow模型
     */
    public String analyzeVideo(ExerciseVideo video) {
        try {
            // 模拟分析过程（睡眠2秒模拟处理时间）
            Thread.sleep(2000);

            // 根据运动类型生成不同的分析结果
            String exerciseType = video.getExerciseType();
            ObjectNode result = objectMapper.createObjectNode();

            if ("SQUAT".equalsIgnoreCase(exerciseType)) {
                // 深蹲分析结果
                result.put("score", 85.5);
                result.put("overallFeedback", "动作基本标准，膝盖稳定性需要加强");
                result.put("duration", "12.5秒");
                result.put("repCount", 8);

                // 详细分析
                ObjectNode details = objectMapper.createObjectNode();
                details.put("kneeAlignment", "良好");
                details.put("backStraightness", "轻微弯曲");
                details.put("depth", "足够");
                details.put("speed", "适中");
                result.set("details", details);

                // 建议
                result.putArray("suggestions")
                        .add("保持背部挺直")
                        .add("控制下蹲速度")
                        .add("注意膝盖不要内扣");

            } else if ("PUSHUP".equalsIgnoreCase(exerciseType)) {
                // 俯卧撑分析结果
                result.put("score", 78.0);
                result.put("overallFeedback", "手臂弯曲角度需要改进");
                result.put("duration", "15.2秒");
                result.put("repCount", 10);
            } else if ("BENCH_PRESS".equalsIgnoreCase(exerciseType)) {
                result.put("score", 82.0);
                result.put("overallFeedback", "卧推动作稳定，建议继续控制下放速度");
                result.put("duration", "11.0秒");
                result.put("repCount", 8);
            } else if ("DEADLIFT".equalsIgnoreCase(exerciseType)) {
                result.put("score", 80.0);
                result.put("overallFeedback", "硬拉动作完成良好，注意髋主导发力");
                result.put("duration", "10.8秒");
                result.put("repCount", 6);
            } else if ("DUMBBELL_SHOULDER_PRESS".equalsIgnoreCase(exerciseType)) {
                result.put("score", 81.0);
                result.put("overallFeedback", "推肩轨迹基本稳定，注意核心收紧");
                result.put("duration", "12.1秒");
                result.put("repCount", 10);
            } else if ("DUMBBELL_LATERAL_RAISE".equalsIgnoreCase(exerciseType)) {
                result.put("score", 79.5);
                result.put("overallFeedback", "侧平举幅度尚可，建议降低借力");
                result.put("duration", "12.3秒");
                result.put("repCount", 12);
            } else if ("DUMBBELL_BICEP_CURL".equalsIgnoreCase(exerciseType)) {
                result.put("score", 83.0);
                result.put("overallFeedback", "弯举节奏良好，建议控制离心下放");
                result.put("duration", "11.4秒");
                result.put("repCount", 12);
            } else if ("PULL_UP".equalsIgnoreCase(exerciseType)) {
                result.put("score", 77.0);
                result.put("overallFeedback", "引体向上完成度中等，建议提高顶峰收缩");
                result.put("duration", "13.2秒");
                result.put("repCount", 6);
            } else {
                // 通用分析结果
                result.put("score", 75.0);
                result.put("overallFeedback", "动作完成，建议参考标准动作改进");
                result.put("duration", "10.0秒");
                result.put("repCount", 5);
            }

            return result.toString();

        } catch (Exception e) {
            // 生成错误结果
            ObjectNode errorResult = objectMapper.createObjectNode();
            errorResult.put("error", "分析失败: " + e.getMessage());
            errorResult.put("score", 0);
            return errorResult.toString();
        }
    }
}
