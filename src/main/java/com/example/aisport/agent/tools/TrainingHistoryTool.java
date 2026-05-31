package com.example.aisport.agent.tools;

import com.example.aisport.agent.AgentContext;
import com.example.aisport.agent.AgentTool;
import com.example.aisport.entity.ExerciseVideo;
import com.example.aisport.entity.User;
import com.example.aisport.repository.ExerciseVideoRepository;
import com.example.aisport.service.TrainingInsightService;
import com.example.aisport.service.UserService;
import org.springframework.stereotype.Component;

import java.util.*;

@Component
public class TrainingHistoryTool implements AgentTool {

    private final ExerciseVideoRepository videoRepository;
    private final UserService userService;
    private final TrainingInsightService trainingInsightService;

    public TrainingHistoryTool(ExerciseVideoRepository videoRepository,
                                UserService userService,
                                TrainingInsightService trainingInsightService) {
        this.videoRepository = videoRepository;
        this.userService = userService;
        this.trainingInsightService = trainingInsightService;
    }

    @Override
    public String name() { return "get_training_history"; }

    @Override
    public String description() {
        return "Get user's recent training records with scores and exercise types. "
             + "Use this to understand user's training frequency, recent performance, and exercise distribution.";
    }

    @Override
    public Map<String, Object> schema() {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("type", "object");
        s.put("description", "Get recent training history. limit defaults to 10 if not specified.");
        Map<String, Object> props = new LinkedHashMap<>();
        Map<String, Object> limitProp = new LinkedHashMap<>();
        limitProp.put("type", "integer");
        limitProp.put("description", "Number of recent records to fetch (max 20)");
        limitProp.put("default", 10);
        props.put("limit", limitProp);
        s.put("properties", props);
        return s;
    }

    @Override
    public Map<String, Object> execute(AgentContext context, Map<String, Object> args) {
        int limit = args != null && args.get("limit") instanceof Number n
                ? Math.min(n.intValue(), 20) : 10;

        Optional<User> userOpt = userService.findByUsername(context.getUsername());
        if (userOpt.isEmpty()) {
            return Map.of("error", "User not found");
        }

        List<ExerciseVideo> videos = videoRepository.findByUser(userOpt.get());
        List<ExerciseVideo> completed = videos.stream()
                .filter(v -> v.getStatus() == ExerciseVideo.VideoStatus.COMPLETED)
                .filter(v -> v.getUploadedAt() != null)
                .sorted(Comparator.comparing(ExerciseVideo::getUploadedAt).reversed())
                .limit(limit)
                .toList();

        List<Map<String, Object>> records = new ArrayList<>();
        for (ExerciseVideo v : completed) {
            Map<String, Object> analysis = trainingInsightService.parseAnalysis(v.getAnalysisResult());
            Map<String, Object> score = trainingInsightService.calculateScore(v.getExerciseType(), analysis);

            Map<String, Object> rec = new LinkedHashMap<>();
            rec.put("videoId", v.getId());
            rec.put("exerciseType", v.getExerciseType());
            rec.put("uploadedAt", v.getUploadedAt() != null ? v.getUploadedAt().toString() : null);
            rec.put("repCount", score.get("repCount"));
            rec.put("finalScore", score.get("finalScore"));
            rec.put("level", score.get("level"));
            rec.put("formScore", score.get("formScore"));
            rec.put("rhythmScore", score.get("rhythmScore"));
            rec.put("symmetryScore", score.get("symmetryScore"));
            rec.put("avgMinAngle", score.get("avgMinAngle"));
            records.add(rec);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("totalCompleted", completed.size());
        result.put("records", records);

        double avg = records.stream()
                .mapToDouble(r -> ((Number) r.getOrDefault("finalScore", 0)).doubleValue())
                .average().orElse(0);
        result.put("averageScore", Math.round(avg * 10.0) / 10.0);

        return result;
    }
}
