package com.example.aisport.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;

@Service
public class PythonAgentClient {

    private static final Logger log = LoggerFactory.getLogger(PythonAgentClient.class);

    private final String pythonAgentUrl;
    private final HttpClient httpClient;
    private final ObjectMapper mapper = new ObjectMapper();

    public PythonAgentClient(@Value("${python.agent.url:http://localhost:8000}") String pythonAgentUrl) {
        this.pythonAgentUrl = pythonAgentUrl;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    public boolean isAvailable() {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(pythonAgentUrl + "/health"))
                    .timeout(Duration.ofSeconds(3))
                    .GET()
                    .build();
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            return resp.statusCode() == 200;
        } catch (Exception e) {
            log.debug("Python Agent not available: {}", e.getMessage());
            return false;
        }
    }

    public Optional<AgentAnswer> chat(AgentContext context) {
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("user_id", context.getUserId());
            payload.put("username", context.getUsername());
            payload.put("question", context.getQuestion());
            payload.put("focus_video_id", context.getFocusVideoId());

            String body = mapper.writeValueAsString(payload);

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(pythonAgentUrl + "/agent/chat"))
                    .timeout(Duration.ofSeconds(60))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();

            long start = System.currentTimeMillis();
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            long elapsed = System.currentTimeMillis() - start;

            if (resp.statusCode() != 200) {
                log.warn("Python Agent returned status {}: {}", resp.statusCode(), resp.body());
                return Optional.empty();
            }

            AgentAnswer answer = parsePythonResponse(resp.body());
            log.info("Python Agent answered in {}ms for user {}", elapsed, context.getUsername());
            return Optional.of(answer);

        } catch (Exception e) {
            log.warn("Python Agent call failed: {}", e.getMessage());
            return Optional.empty();
        }
    }

    @SuppressWarnings("unchecked")
    private AgentAnswer parsePythonResponse(String json) throws Exception {
        Map<String, Object> data = mapper.readValue(json, Map.class);

        AgentAnswer answer = new AgentAnswer();
        answer.setSummary(String.valueOf(data.getOrDefault("summary", "")));

        // diagnosis
        List<Map<String, Object>> diagList = (List<Map<String, Object>>) data.getOrDefault("diagnosis", List.of());
        for (Map<String, Object> d : diagList) {
            AgentAnswer.DiagnosisItem item = new AgentAnswer.DiagnosisItem();
            item.setIssue(String.valueOf(d.getOrDefault("issue", "")));
            item.setEvidence(String.valueOf(d.getOrDefault("evidence", "")));
            item.setSeverity(String.valueOf(d.getOrDefault("severity", "medium")));
            answer.getDiagnosis().add(item);
        }

        // recommendations
        List<Map<String, Object>> recList = (List<Map<String, Object>>) data.getOrDefault("recommendations", List.of());
        for (Map<String, Object> r : recList) {
            AgentAnswer.Recommendation item = new AgentAnswer.Recommendation();
            item.setTitle(String.valueOf(r.getOrDefault("title", "")));
            item.setDetail(String.valueOf(r.getOrDefault("detail", "")));
            item.setPriority(String.valueOf(r.getOrDefault("priority", "medium")));
            answer.getRecommendations().add(item);
        }

        // trainingPlan
        List<Map<String, Object>> planList = (List<Map<String, Object>>) data.getOrDefault("training_plan", List.of());
        for (Map<String, Object> p : planList) {
            AgentAnswer.TrainingPlanItem item = new AgentAnswer.TrainingPlanItem();
            item.setDay(String.valueOf(p.getOrDefault("day", "")));
            item.setContent(String.valueOf(p.getOrDefault("content", "")));
            item.setFocus(String.valueOf(p.getOrDefault("focus", "")));
            answer.getTrainingPlan().add(item);
        }

        // references
        List<Map<String, Object>> refList = (List<Map<String, Object>>) data.getOrDefault("references", List.of());
        for (Map<String, Object> r : refList) {
            AgentAnswer.ReferenceItem item = new AgentAnswer.ReferenceItem();
            item.setType(String.valueOf(r.getOrDefault("type", "")));
            item.setTitle(String.valueOf(r.getOrDefault("title", "")));
            item.setSnippet(String.valueOf(r.getOrDefault("snippet", "")));
            answer.getReferences().add(item);
        }

        // toolCalls
        List<Map<String, Object>> tcList = (List<Map<String, Object>>) data.getOrDefault("tool_calls", List.of());
        for (Map<String, Object> tc : tcList) {
            ToolCallRecord record = new ToolCallRecord(
                    String.valueOf(tc.getOrDefault("tool", "")),
                    Boolean.TRUE.equals(tc.get("success")),
                    String.valueOf(tc.getOrDefault("summary", "")),
                    tc.get("duration_ms") instanceof Number n ? n.longValue() : 0
            );
            answer.getToolCalls().add(record);
        }

        return answer;
    }
}
