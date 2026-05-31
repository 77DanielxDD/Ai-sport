package com.example.aisport.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@ConditionalOnProperty(name = "app.llm.enabled", havingValue = "true", matchIfMissing = true)
public class LLMInsightService {

    private static final Logger log = LoggerFactory.getLogger(LLMInsightService.class);

    private final RestTemplate restTemplate;
    private final ObjectMapper mapper;

    @Value("${app.llm.api-key:}")
    private String apiKey;

    @Value("${app.llm.base-url:https://api.deepseek.com}")
    private String baseUrl;

    @Value("${app.llm.model:deepseek-chat}")
    private String model;

    protected LLMInsightService() {
        this.restTemplate = new RestTemplateBuilder()
                .connectTimeout(Duration.ofMillis(15000))
                .readTimeout(Duration.ofMillis(15000))
                .build();
        this.mapper = new ObjectMapper();
    }

    public LLMInsightService(@Value("${app.llm.timeout-ms:15000}") int timeoutMs) {
        this.restTemplate = new RestTemplateBuilder()
                .connectTimeout(Duration.ofMillis(timeoutMs))
                .readTimeout(Duration.ofMillis(timeoutMs))
                .build();
        this.mapper = new ObjectMapper();
    }

    LLMInsightService(String apiKey, String baseUrl, String model, RestTemplate restTemplate) {
        this.apiKey = apiKey;
        this.baseUrl = baseUrl;
        this.model = model;
        this.restTemplate = restTemplate;
        this.mapper = new ObjectMapper();
    }

    public Map<String, Object> generateInsight(Map<String, Object> analysisData) {
        if (apiKey == null || apiKey.isBlank()) {
            log.warn("LLM API key not configured, skipping insight generation");
            return null;
        }

        log.info("Calling LLM for insight generation (model={}, baseUrl={})", model, baseUrl);
        try {
            String prompt = buildPrompt(analysisData);
            Map<String, Object> requestBody = buildRequest(prompt);
            String response = callApi(requestBody);
            Map<String, Object> result = parseResponse(response);
            log.info("LLM insight generated successfully (feedback={}, suggestions={})",
                    result.containsKey("overallFeedback"),
                    result.containsKey("suggestions") ? ((java.util.List<?>) result.get("suggestions")).size() : 0);
            return result;
        } catch (Exception e) {
            log.warn("LLM insight generation failed, falling back to Python result: {} ({})",
                    e.getMessage(), e.getClass().getSimpleName());
            return null;
        }
    }

    private String buildPrompt(Map<String, Object> data) {
        String exerciseType = String.valueOf(data.getOrDefault("exerciseType", "未知动作"));
        double finalScore = toDouble(data.get("finalScore"));
        String level = String.valueOf(data.getOrDefault("level", "未知"));
        double formScore = toDouble(data.get("formScore"));
        int repCount = toInt(data.get("repCount"));
        double avgMinAngle = toDouble(data.get("avgMinAngle"));
        double targetAngle = toDouble(data.get("targetAngle"));
        double rhythmScore = toDouble(data.get("rhythmScore"));
        double symmetryScore = toDouble(data.get("symmetryScore"));
        String tipsJson = buildTipsJson(data.get("tips"));

        return String.format("""
                你是一位资深的健身教练。根据以下运动分析数据，给出个性化的动作评价和改进建议。

                动作类型：%s
                综合评分：%.1f 分（%s）
                幅度得分：%.1f 分（关节角度接近度）
                节奏得分：%.1f 分（动作速度一致性）
                对称性得分：%.1f 分（左右侧均衡度）
                完成次数：%d 次
                实际关节角度：%.1f°
                目标关节角度：%.1f°
                逐次数据：%s

                请返回 JSON 格式（不要包含 markdown 代码块标记）：
                {
                  "overallFeedback": "一句话总结动作表现，语气鼓励但不失专业",
                  "suggestions": ["具体建议1", "具体建议2", "具体建议3"],
                  "repTipsCn": ["第1次：xxx", "第2次：xxx", ...]
                }

                repTipsCn 数组长度必须与逐次数据条数一致，每条结合该次的角度数据给出中文点评。
                建议需覆盖幅度、节奏、对称性三个方面。所有内容用中文输出。""",
                exerciseType, finalScore, level, formScore, rhythmScore, symmetryScore, repCount, avgMinAngle, targetAngle, tipsJson);
    }

    private String buildTipsJson(Object tips) {
        if (!(tips instanceof List<?> list) || list.isEmpty()) {
            return "无";
        }
        try {
            return mapper.writeValueAsString(tips);
        } catch (Exception e) {
            return "无";
        }
    }

    private Map<String, Object> buildRequest(String prompt) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("temperature", 0.7);
        body.put("max_tokens", 1024);

        List<Map<String, String>> messages = new ArrayList<>();

        Map<String, String> systemMsg = new LinkedHashMap<>();
        systemMsg.put("role", "system");
        systemMsg.put("content", "你是一位专业健身教练，回答使用中文，严格按 JSON 格式输出。");
        messages.add(systemMsg);

        Map<String, String> userMsg = new LinkedHashMap<>();
        userMsg.put("role", "user");
        userMsg.put("content", prompt);
        messages.add(userMsg);

        body.put("messages", messages);
        return body;
    }

    private String callApi(Map<String, Object> requestBody) throws Exception {
        String url = baseUrl.replaceAll("/$", "") + "/v1/chat/completions";

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(apiKey);

        String json = mapper.writeValueAsString(requestBody);
        HttpEntity<String> entity = new HttpEntity<>(json, headers);

        log.debug("Sending LLM request to {}", url);
        ResponseEntity<String> response = restTemplate.postForEntity(url, entity, String.class);
        if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
            String bodySnippet = response.getBody() != null
                    ? response.getBody().substring(0, Math.min(300, response.getBody().length()))
                    : "null";
            log.warn("LLM API returned non-ok status: {} body={}", response.getStatusCode(), bodySnippet);
            throw new RuntimeException("DeepSeek API returned " + response.getStatusCode());
        }
        return response.getBody();
    }

    private Map<String, Object> parseResponse(String responseBody) throws Exception {
        Map<String, Object> root = mapper.readValue(responseBody, new TypeReference<>() {});

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> choices = (List<Map<String, Object>>) root.get("choices");
        if (choices == null || choices.isEmpty()) {
            throw new RuntimeException("No choices in response");
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> message = (Map<String, Object>) choices.get(0).get("message");
        if (message == null) {
            throw new RuntimeException("No message in response");
        }

        String content = (String) message.get("content");
        if (content == null || content.isBlank()) {
            throw new RuntimeException("Empty content in response");
        }

        content = content.trim();
        if (content.startsWith("```json")) {
            content = content.substring(7);
        }
        if (content.startsWith("```")) {
            content = content.substring(3);
        }
        if (content.endsWith("```")) {
            content = content.substring(0, content.length() - 3);
        }
        content = content.trim();

        return mapper.readValue(content, new TypeReference<>() {});
    }

    private double toDouble(Object val) {
        if (val == null) return 0.0;
        try {
            return Double.parseDouble(String.valueOf(val));
        } catch (Exception ignored) {
            return 0.0;
        }
    }

    private int toInt(Object val) {
        if (val == null) return 0;
        try {
            return Integer.parseInt(String.valueOf(val));
        } catch (Exception ignored) {
            return 0;
        }
    }
}
