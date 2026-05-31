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
public class ScoreTrendTool implements AgentTool {

    private final ExerciseVideoRepository videoRepository;
    private final UserService userService;
    private final TrainingInsightService trainingInsightService;

    public ScoreTrendTool(ExerciseVideoRepository videoRepository,
                           UserService userService,
                           TrainingInsightService trainingInsightService) {
        this.videoRepository = videoRepository;
        this.userService = userService;
        this.trainingInsightService = trainingInsightService;
    }

    @Override
    public String name() { return "get_score_trend"; }

    @Override
    public String description() {
        return "Get user's training score trends over a specified number of days. "
             + "Returns daily aggregated scores, overall score, and session counts. "
             + "Use this to answer questions about progress trends and improvement over time.";
    }

    @Override
    public Map<String, Object> schema() {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("type", "object");
        s.put("description", "Get score trends. Default days=30 if not specified.");
        Map<String, Object> props = new LinkedHashMap<>();
        Map<String, Object> daysProp = new LinkedHashMap<>();
        daysProp.put("type", "integer");
        daysProp.put("description", "Number of days to look back (7, 30, or 90)");
        daysProp.put("default", 30);
        props.put("days", daysProp);
        s.put("properties", props);
        return s;
    }

    @Override
    public Map<String, Object> execute(AgentContext context, Map<String, Object> args) {
        int days = args != null && args.get("days") instanceof Number n
                ? n.intValue() : 30;
        days = Math.max(7, Math.min(days, 180));

        Optional<User> userOpt = userService.findByUsername(context.getUsername());
        if (userOpt.isEmpty()) {
            return Map.of("error", "User not found");
        }

        List<ExerciseVideo> videos = videoRepository.findByUser(userOpt.get());
        Map<String, Object> trends = trainingInsightService.buildTrends(videos, days);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("days", days);
        result.put("completedSessions", trends.get("completedSessions"));
        result.put("overallScore", trends.get("overallScore"));
        result.put("daily", trends.get("daily"));
        result.put("recentScores", trends.get("recentScores"));
        result.put("computedAt", trends.get("computedAt"));

        return result;
    }
}
