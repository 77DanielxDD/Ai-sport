package com.example.aisport.agent.tools;

import com.example.aisport.agent.AgentContext;
import com.example.aisport.agent.AgentTool;
import com.example.aisport.entity.ExerciseVideo;
import com.example.aisport.service.TrainingInsightService;
import com.example.aisport.service.VideoService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.*;

@Component
public class VideoReportTool implements AgentTool {

    private final VideoService videoService;
    private final TrainingInsightService trainingInsightService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public VideoReportTool(VideoService videoService,
                            TrainingInsightService trainingInsightService) {
        this.videoService = videoService;
        this.trainingInsightService = trainingInsightService;
    }

    @Override
    public String name() { return "get_video_report"; }

    @Override
    public String description() {
        return "Get detailed analysis report for a specific video by videoId. "
             + "Includes rep count, per-rep angles, tips, rhythm, symmetry, and score breakdown. "
             + "Use this to answer questions about a specific training session's performance.";
    }

    @Override
    public Map<String, Object> schema() {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("type", "object");
        s.put("description", "Get video analysis report. videoId is required.");
        Map<String, Object> props = new LinkedHashMap<>();
        Map<String, Object> idProp = new LinkedHashMap<>();
        idProp.put("type", "integer");
        idProp.put("description", "Video ID to get report for");
        props.put("videoId", idProp);
        s.put("properties", props);
        s.put("required", List.of("videoId"));
        return s;
    }

    @Override
    public Map<String, Object> execute(AgentContext context, Map<String, Object> args) {
        Long videoId = args != null && args.get("videoId") instanceof Number n
                ? n.longValue() : context.getFocusVideoId();

        if (videoId == null) {
            return Map.of("error", "videoId is required, no focus video available");
        }

        Optional<ExerciseVideo> videoOpt = videoService.findById(videoId);
        if (videoOpt.isEmpty()) {
            return Map.of("error", "Video not found: " + videoId);
        }

        ExerciseVideo video = videoOpt.get();
        if (video.getStatus() != ExerciseVideo.VideoStatus.COMPLETED) {
            return Map.of("error", "Video status is " + video.getStatus() + ", not yet completed");
        }

        try {
            String resultJson = videoService.getAnalysisResult(videoId);
            Map<String, Object> analysis = objectMapper.readValue(resultJson, new TypeReference<>() {});
            Map<String, Object> score = trainingInsightService.calculateScore(video.getExerciseType(), analysis);

            Map<String, Object> report = new LinkedHashMap<>();
            report.put("videoId", videoId);
            report.put("exerciseType", video.getExerciseType());
            report.put("uploadedAt", video.getUploadedAt() != null ? video.getUploadedAt().toString() : null);
            report.put("scoreBreakdown", score);

            List<Map<String, Object>> tips = extractTips(analysis.get("tips"));
            report.put("tips", tips);
            report.put("repCount", tips.size());

            report.put("rhythm", analysis.get("rhythm"));
            report.put("symmetry", analysis.get("symmetry"));
            report.put("overallFeedback", analysis.get("overall_feedback"));
            report.put("suggestions", analysis.get("suggestions"));
            report.put("reportImages", analysis.get("report_images"));

            return report;
        } catch (Exception e) {
            return Map.of("error", "Failed to get report: " + e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> extractTips(Object tipsObj) {
        if (!(tipsObj instanceof List<?> list)) return List.of();
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> m) {
                Map<String, Object> tip = new LinkedHashMap<>();
                tip.put("repIndex", m.get("rep_index"));
                tip.put("minAngle", m.get("min_angle"));
                tip.put("tip", m.get("tip"));
                tip.put("tipCn", m.get("tip_cn"));
                out.add(tip);
            }
        }
        return out;
    }
}
