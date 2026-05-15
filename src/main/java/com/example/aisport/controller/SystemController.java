package com.example.aisport.controller;

import com.example.aisport.entity.ExerciseVideo;
import com.example.aisport.repository.ExerciseVideoRepository;
import com.example.aisport.service.StorageCleanupService;
import com.example.aisport.service.UserService;
import com.example.aisport.task.AnalysisTaskRepository;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestTemplate;

import java.security.Principal;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/system")
public class SystemController {

    private final JdbcTemplate jdbcTemplate;
    private final RabbitTemplate rabbitTemplate;
    private final RestTemplate restTemplate;
    private final UserService userService;
    private final StorageCleanupService storageCleanupService;
    private final MeterRegistry meterRegistry;
    private final ExerciseVideoRepository exerciseVideoRepository;
    private final AnalysisTaskRepository analysisTaskRepository;

    @Value("${ai.service.base-url:http://127.0.0.1:8000}")
    private String aiBaseUrl;

    public SystemController(JdbcTemplate jdbcTemplate,
                            RabbitTemplate rabbitTemplate,
                            RestTemplate restTemplate,
                            UserService userService,
                            StorageCleanupService storageCleanupService,
                            MeterRegistry meterRegistry,
                            ExerciseVideoRepository exerciseVideoRepository,
                            AnalysisTaskRepository analysisTaskRepository) {
        this.jdbcTemplate = jdbcTemplate;
        this.rabbitTemplate = rabbitTemplate;
        this.restTemplate = restTemplate;
        this.userService = userService;
        this.storageCleanupService = storageCleanupService;
        this.meterRegistry = meterRegistry;
        this.exerciseVideoRepository = exerciseVideoRepository;
        this.analysisTaskRepository = analysisTaskRepository;
    }

    @GetMapping("/health")
    public ResponseEntity<?> health() {
        Map<String, Object> db = checkDb();
        Map<String, Object> mq = checkMq();
        Map<String, Object> py = checkPython();

        Map<String, Object> checks = new LinkedHashMap<>();
        checks.put("database", db);
        checks.put("rabbitmq", mq);
        checks.put("python_ai", py);

        boolean dbUp = "UP".equals(db.get("status"));
        boolean pyUp = "UP".equals(py.get("status"));
        boolean mqUp = "UP".equals(mq.get("status"));

        boolean coreUp = dbUp && pyUp;
        String status = coreUp ? (mqUp ? "UP" : "DEGRADED") : "DOWN";

        Map<String, Object> body = new HashMap<>();
        body.put("status", status);
        body.put("checks", checks);
        body.put("degradedReason", mqUp ? null : "RabbitMQ unavailable, local async fallback enabled");
        return ResponseEntity.status(coreUp ? 200 : 503).body(body);
    }

    @PostMapping("/cleanup/run")
    public ResponseEntity<?> runCleanup(Principal principal) {
        if (principal == null || !userService.isAdmin(principal.getName())) {
            return ResponseEntity.status(403).body(Map.of("error", "Admin required"));
        }
        return ResponseEntity.ok(storageCleanupService.runCleanupNow());
    }

    @GetMapping("/metrics/summary")
    public ResponseEntity<?> metricsSummary(Principal principal) {
        if (principal == null || !userService.isAdmin(principal.getName())) {
            return ResponseEntity.status(403).body(Map.of("error", "Admin required"));
        }

        double cpuProcess = gauge("process.cpu.usage");
        double cpuSystem = gauge("system.cpu.usage");
        double jvmUsed = sumGaugeByPrefix("jvm.memory.used");

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("processCpuUsage", round4(cpuProcess));
        out.put("systemCpuUsage", round4(cpuSystem));
        out.put("jvmMemoryUsedMb", round2(jvmUsed / 1024.0 / 1024.0));
        out.put("httpServerRequestsCount", round0(counter("http.server.requests")));
        out.put("videosTotal", exerciseVideoRepository.count());
        out.put("videosCompleted", exerciseVideoRepository.countByStatus(ExerciseVideo.VideoStatus.COMPLETED));
        out.put("videosFailed", exerciseVideoRepository.countByStatus(ExerciseVideo.VideoStatus.FAILED));
        out.put("tasksTotal", analysisTaskRepository.count());
        out.put("computedAt", java.time.LocalDateTime.now());
        return ResponseEntity.ok(out);
    }

    private Map<String, Object> checkDb() {
        Map<String, Object> result = new HashMap<>();
        try {
            Integer one = jdbcTemplate.queryForObject("SELECT 1", Integer.class);
            if (one != null && one == 1) {
                result.put("status", "UP");
                result.put("detail", "SELECT 1 ok");
                return result;
            }
            result.put("status", "DOWN");
            result.put("detail", "unexpected db query result");
            return result;
        } catch (Exception e) {
            result.put("status", "DOWN");
            result.put("detail", e.getMessage());
            return result;
        }
    }

    private Map<String, Object> checkMq() {
        Map<String, Object> result = new HashMap<>();
        try {
            rabbitTemplate.execute(channel -> channel.isOpen());
            result.put("status", "UP");
            result.put("detail", "channel open");
            return result;
        } catch (Exception e) {
            result.put("status", "DOWN");
            result.put("detail", e.getMessage());
            return result;
        }
    }

    private Map<String, Object> checkPython() {
        Map<String, Object> result = new HashMap<>();
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> resp = restTemplate.getForObject(aiBaseUrl + "/health", Map.class);
            if (resp != null && "ok".equals(String.valueOf(resp.get("status")))) {
                result.put("status", "UP");
                result.put("detail", "python /health ok");
                return result;
            }
            result.put("status", "DOWN");
            result.put("detail", "python /health unexpected response");
            return result;
        } catch (Exception e) {
            result.put("status", "DOWN");
            result.put("detail", e.getMessage());
            return result;
        }
    }

    private double gauge(String meterName) {
        try {
            Double val = meterRegistry.find(meterName).gauge() == null ? null : meterRegistry.find(meterName).gauge().value();
            return val == null ? 0.0 : val;
        } catch (Exception ignored) {
            return 0.0;
        }
    }

    private double sumGaugeByPrefix(String prefix) {
        return meterRegistry.getMeters().stream()
                .filter(m -> m.getId().getName().startsWith(prefix))
                .mapToDouble(m -> {
                    try {
                        return m.measure().iterator().next().getValue();
                    } catch (Exception ignored) {
                        return 0.0;
                    }
                })
                .sum();
    }

    private double counter(String meterName) {
        try {
            return meterRegistry.find(meterName).counter() == null ? 0.0 : meterRegistry.find(meterName).counter().count();
        } catch (Exception ignored) {
            return 0.0;
        }
    }

    private double round4(double v) {
        return Math.round(v * 10000.0) / 10000.0;
    }

    private long round0(double v) {
        return Math.round(v);
    }

    private double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
