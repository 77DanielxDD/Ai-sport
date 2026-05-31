package com.example.aisport.controller;

import com.example.aisport.entity.ExerciseVideo;
import com.example.aisport.entity.User;
import com.example.aisport.exception.UnauthorizedAccessException;
import com.example.aisport.service.TrainingInsightService;
import com.example.aisport.service.UserService;
import com.example.aisport.service.VideoService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/videos")
public class VideoInsightController {

    private final VideoService videoService;
    private final UserService userService;
    private final TrainingInsightService trainingInsightService;

    public VideoInsightController(VideoService videoService,
                                   UserService userService,
                                   TrainingInsightService trainingInsightService) {
        this.videoService = videoService;
        this.userService = userService;
        this.trainingInsightService = trainingInsightService;
    }

    @GetMapping("/trends")
    public ResponseEntity<?> getTrainingTrends(@RequestParam(required = false, defaultValue = "30") Integer days,
                                               Principal principal) {
        if (principal == null) {
            return ResponseEntity.status(org.springframework.http.HttpStatus.UNAUTHORIZED).body(Map.of("error", "Unauthorized"));
        }
        User user = userService.findByUsername(principal.getName())
                .orElseThrow(() -> new UnauthorizedAccessException(null, "User not found"));
        List<ExerciseVideo> videos = videoService.findByUser(user);
        Map<String, Object> trends = trainingInsightService.buildTrends(videos, days == null ? 30 : days);
        trends.put("username", principal.getName());
        return ResponseEntity.ok(trends);
    }
}
