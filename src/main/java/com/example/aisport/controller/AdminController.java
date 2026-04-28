package com.example.aisport.controller;

import com.example.aisport.entity.ExerciseVideo;
import com.example.aisport.entity.User;
import com.example.aisport.repository.ExerciseVideoRepository;
import com.example.aisport.repository.UserRepository;
import com.example.aisport.service.UserService;
import com.example.aisport.service.VideoService;
import com.example.aisport.task.AnalysisTask;
import com.example.aisport.task.AnalysisTaskRepository;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private final UserService userService;
    private final UserRepository userRepository;
    private final ExerciseVideoRepository videoRepository;
    private final AnalysisTaskRepository taskRepository;
    private final VideoService videoService;

    public AdminController(UserService userService,
                           UserRepository userRepository,
                           ExerciseVideoRepository videoRepository,
                           AnalysisTaskRepository taskRepository,
                           VideoService videoService) {
        this.userService = userService;
        this.userRepository = userRepository;
        this.videoRepository = videoRepository;
        this.taskRepository = taskRepository;
        this.videoService = videoService;
    }

    @GetMapping("/overview")
    public ResponseEntity<?> overview(Principal principal) {
        ResponseEntity<Map<String, Object>> forbidden = requireAdmin(principal);
        if (forbidden != null) return forbidden;

        List<User> users = userRepository.findAll();
        List<ExerciseVideo> videos = videoRepository.findAll();
        List<AnalysisTask> tasks = taskRepository.findAll();

        Map<String, Long> videoStatus = videos.stream()
                .collect(Collectors.groupingBy(v -> v.getStatus().name(), Collectors.counting()));
        Map<String, Long> taskStatus = tasks.stream()
                .collect(Collectors.groupingBy(t -> t.getStatus().name(), Collectors.counting()));

        long enabledUsers = users.stream().filter(u -> u.getEnabled() == null || u.getEnabled()).count();
        long disabledUsers = users.size() - enabledUsers;

        return ResponseEntity.ok(Map.of(
                "users", Map.of(
                        "total", users.size(),
                        "enabled", enabledUsers,
                        "disabled", disabledUsers,
                        "admins", users.stream().filter(u -> u.getRole() == User.UserRole.ADMIN).count()
                ),
                "videos", Map.of(
                        "total", videos.size(),
                        "byStatus", videoStatus
                ),
                "tasks", Map.of(
                        "total", tasks.size(),
                        "byStatus", taskStatus
                )
        ));
    }

    @GetMapping("/users")
    public ResponseEntity<?> users(Principal principal) {
        ResponseEntity<Map<String, Object>> forbidden = requireAdmin(principal);
        if (forbidden != null) return forbidden;

        List<Map<String, Object>> items = userRepository.findTop100ByOrderByIdDesc().stream()
                .map(u -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id", u.getId());
                    row.put("username", u.getUsername());
                    row.put("email", u.getEmail());
                    row.put("role", u.getRole() == null ? "USER" : u.getRole().name());
                    row.put("enabled", u.getEnabled() == null || u.getEnabled());
                    row.put("createdAt", u.getCreatedAt());
                    return row;
                }).toList();

        return ResponseEntity.ok(Map.of("items", items, "count", items.size()));
    }

    @PostMapping("/users/{userId}/enabled")
    public ResponseEntity<?> setUserEnabled(@PathVariable Long userId,
                                            @RequestBody Map<String, Object> body,
                                            Principal principal) {
        ResponseEntity<Map<String, Object>> forbidden = requireAdmin(principal);
        if (forbidden != null) return forbidden;

        boolean enabled = Boolean.TRUE.equals(body.get("enabled"));

        User me = userService.findByUsername(principal.getName()).orElse(null);
        User target = userRepository.findById(userId).orElse(null);
        if (target == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "User not found"));
        }
        if (me != null && Objects.equals(me.getId(), target.getId()) && !enabled) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", "Admin cannot disable self"));
        }

        target.setEnabled(enabled);
        userRepository.save(target);
        return ResponseEntity.ok(Map.of(
                "userId", target.getId(),
                "username", target.getUsername(),
                "enabled", target.getEnabled() == null || target.getEnabled()
        ));
    }

    @PostMapping("/users")
    public ResponseEntity<?> createUser(@RequestBody Map<String, Object> body, Principal principal) {
        ResponseEntity<Map<String, Object>> forbidden = requireAdmin(principal);
        if (forbidden != null) return forbidden;

        String username = body.get("username") == null ? null : String.valueOf(body.get("username")).trim();
        String password = body.get("password") == null ? null : String.valueOf(body.get("password"));
        String email = body.get("email") == null ? null : String.valueOf(body.get("email"));
        String role = body.get("role") == null ? "USER" : String.valueOf(body.get("role"));
        Boolean enabled = body.get("enabled") == null ? Boolean.TRUE : Boolean.valueOf(String.valueOf(body.get("enabled")));

        try {
            User created = userService.createUserByAdmin(username, password, email, role, enabled);
            return ResponseEntity.ok(Map.of(
                    "id", created.getId(),
                    "username", created.getUsername(),
                    "role", created.getRole() == null ? "USER" : created.getRole().name(),
                    "enabled", created.getEnabled() == null || created.getEnabled()
            ));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @DeleteMapping("/users/{userId}")
    public ResponseEntity<?> deleteUser(@PathVariable Long userId, Principal principal) {
        ResponseEntity<Map<String, Object>> forbidden = requireAdmin(principal);
        if (forbidden != null) return forbidden;

        User me = userService.findByUsername(principal.getName()).orElse(null);
        User target = userRepository.findById(userId).orElse(null);
        if (target == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "User not found"));
        }
        if (me != null && Objects.equals(me.getId(), target.getId())) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", "Admin cannot delete self"));
        }

        List<ExerciseVideo> videos = new ArrayList<>(videoService.findByUser(target));
        for (ExerciseVideo v : videos) {
            videoService.deleteVideoCascade(v);
        }
        userService.deleteUser(target);

        return ResponseEntity.ok(Map.of("message", "User deleted", "userId", userId));
    }

    @GetMapping("/videos")
    public ResponseEntity<?> videos(Principal principal) {
        ResponseEntity<Map<String, Object>> forbidden = requireAdmin(principal);
        if (forbidden != null) return forbidden;

        List<Map<String, Object>> items = videoRepository.findTop100ByOrderByIdDesc().stream().map(v -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", v.getId());
            row.put("username", v.getUser() == null ? null : v.getUser().getUsername());
            row.put("exerciseType", v.getExerciseType());
            row.put("status", v.getStatus() == null ? null : v.getStatus().name());
            row.put("uploadedAt", v.getUploadedAt());
            row.put("processedAt", v.getProcessedAt());
            row.put("errorCode", v.getErrorCode());
            return row;
        }).toList();

        return ResponseEntity.ok(Map.of("items", items, "count", items.size()));
    }


    @PostMapping("/videos/{videoId}/report-images/migrate-cos")
    public ResponseEntity<?> migrateReportImagesToCos(@PathVariable Long videoId, Principal principal) {
        ResponseEntity<Map<String, Object>> forbidden = requireAdmin(principal);
        if (forbidden != null) return forbidden;

        ExerciseVideo video = videoRepository.findById(videoId).orElse(null);
        if (video == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "Video not found"));
        }
        try {
            return ResponseEntity.ok(videoService.migrateReportImagesToCos(video));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", e.getMessage()));
        }
    }
    @GetMapping("/tasks")
    public ResponseEntity<?> tasks(Principal principal) {
        ResponseEntity<Map<String, Object>> forbidden = requireAdmin(principal);
        if (forbidden != null) return forbidden;

        List<Map<String, Object>> items = taskRepository.findTop100ByOrderByIdDesc().stream().map(t -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", t.getId());
            row.put("videoId", t.getVideoId());
            row.put("status", t.getStatus() == null ? null : t.getStatus().name());
            row.put("attempt", t.getAttempt());
            row.put("queuedAt", t.getQueuedAt());
            row.put("startedAt", t.getStartedAt());
            row.put("finishedAt", t.getFinishedAt());
            row.put("errorCode", t.getErrorCode());
            return row;
        }).toList();

        return ResponseEntity.ok(Map.of("items", items, "count", items.size()));
    }

    private ResponseEntity<Map<String, Object>> requireAdmin(Principal principal) {
        if (principal == null || !userService.isAdmin(principal.getName())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "Admin required"));
        }
        return null;
    }
}
