package com.example.aisport.memory;

import com.example.aisport.entity.ExerciseVideo;
import com.example.aisport.repository.ExerciseVideoRepository;
import com.example.aisport.service.TrainingInsightService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Service
public class UserMemoryRefreshService {

    private static final Logger log = LoggerFactory.getLogger(UserMemoryRefreshService.class);

    private final ExerciseVideoRepository videoRepository;
    private final TrainingInsightService trainingInsightService;
    private final UserMemoryService userMemoryService;

    public UserMemoryRefreshService(ExerciseVideoRepository videoRepository,
                                     TrainingInsightService trainingInsightService,
                                     UserMemoryService userMemoryService) {
        this.videoRepository = videoRepository;
        this.trainingInsightService = trainingInsightService;
        this.userMemoryService = userMemoryService;
    }

    @Scheduled(cron = "0 30 2 * * ?")
    public void refreshAllMemories() {
        log.info("Starting scheduled refresh of all user memories");
        List<ExerciseVideo> allVideos = videoRepository.findTop100ByOrderByIdDesc();

        Map<Long, List<ExerciseVideo>> byUser = allVideos.stream()
                .filter(v -> v.getUser() != null && v.getStatus() == ExerciseVideo.VideoStatus.COMPLETED)
                .collect(Collectors.groupingBy(v -> v.getUser().getId()));

        for (Map.Entry<Long, List<ExerciseVideo>> entry : byUser.entrySet()) {
            try {
                refreshUserMemory(entry.getKey(), entry.getValue());
            } catch (Exception e) {
                log.warn("Failed to refresh memory for user {}: {}", entry.getKey(), e.getMessage());
            }
        }
        log.info("Scheduled refresh complete for {} users", byUser.size());
    }

    public void refreshUserMemory(Long userId, List<ExerciseVideo> videos) {
        if (videos.isEmpty()) return;

        String username = videos.get(0).getUser().getUsername();

        Map<String, List<ExerciseVideo>> byType = videos.stream()
                .filter(v -> v.getExerciseType() != null)
                .collect(Collectors.groupingBy(ExerciseVideo::getExerciseType));

        List<String> weakTypes = new ArrayList<>();
        List<String> allMistakes = new ArrayList<>();
        double totalScore = 0;
        int scoreCount = 0;

        for (Map.Entry<String, List<ExerciseVideo>> typeEntry : byType.entrySet()) {
            double typeAvg = 0;
            int typeCount = 0;
            for (ExerciseVideo v : typeEntry.getValue()) {
                Map<String, Object> analysis = trainingInsightService.parseAnalysis(v.getAnalysisResult());
                Map<String, Object> score = trainingInsightService.calculateScore(v.getExerciseType(), analysis);
                Object fs = score.get("finalScore");
                if (fs instanceof Number n) {
                    typeAvg += n.doubleValue();
                    typeCount++;
                    totalScore += n.doubleValue();
                    scoreCount++;
                }
                extractMistakes(analysis, allMistakes);
            }
            if (typeCount > 0) {
                typeAvg /= typeCount;
                if (typeAvg < 60.0) {
                    weakTypes.add(typeEntry.getKey() + "(" + Math.round(typeAvg) + "分)");
                }
            }
        }

        Map<String, Object> profile = new LinkedHashMap<>();
        profile.put("totalSessions", videos.size());
        profile.put("avgScore", scoreCount > 0 ? Math.round(totalScore / scoreCount * 10.0) / 10.0 : null);
        profile.put("scoreTrend", computeTrend(videos));
        profile.put("weakExerciseTypes", String.join(", ", weakTypes));
        profile.put("commonMistakes", allMistakes.stream().distinct().limit(5).collect(Collectors.joining("; ")));
        profile.put("exerciseTypeDistribution", byType.keySet().stream().toList());
        profile.put("refreshedAt", java.time.LocalDateTime.now().toString());

        userMemoryService.saveProfile(userId, username, profile);
    }

    private String computeTrend(List<ExerciseVideo> videos) {
        List<ExerciseVideo> sorted = videos.stream()
                .filter(v -> v.getUploadedAt() != null)
                .sorted(Comparator.comparing(ExerciseVideo::getUploadedAt))
                .toList();
        if (sorted.size() < 4) return "insufficient_data";

        int half = sorted.size() / 2;
        double firstHalf = avgScore(sorted.subList(0, half));
        double secondHalf = avgScore(sorted.subList(half, sorted.size()));
        double diff = secondHalf - firstHalf;
        if (diff > 3.0) return "improving";
        if (diff < -3.0) return "declining";
        return "stable";
    }

    private double avgScore(List<ExerciseVideo> videos) {
        double sum = 0;
        int count = 0;
        for (ExerciseVideo v : videos) {
            Map<String, Object> analysis = trainingInsightService.parseAnalysis(v.getAnalysisResult());
            Map<String, Object> score = trainingInsightService.calculateScore(v.getExerciseType(), analysis);
            if (score.get("finalScore") instanceof Number n) {
                sum += n.doubleValue();
                count++;
            }
        }
        return count > 0 ? sum / count : 0;
    }

    @SuppressWarnings("unchecked")
    private void extractMistakes(Map<String, Object> analysis, List<String> mistakes) {
        try {
            Object tipsObj = analysis.get("tips");
            if (tipsObj instanceof List<?> tips) {
                for (Object item : tips) {
                    if (item instanceof Map<?, ?> m) {
                        Object tip = m.get("tip");
                        if (tip != null && !String.valueOf(tip).isBlank()) {
                            String t = String.valueOf(tip).trim();
                            if (t.length() > 5) mistakes.add(t);
                        }
                    }
                }
            }
        } catch (Exception ignored) {}
    }
}
