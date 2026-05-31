package com.example.aisport.agent;

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
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.util.*;

@Component
@ConditionalOnProperty(name = "app.llm.enabled", havingValue = "true", matchIfMissing = true)
public class AgentLLMClient {

    private static final Logger log = LoggerFactory.getLogger(AgentLLMClient.class);

    private final RestTemplate restTemplate;
    private final ObjectMapper mapper = new ObjectMapper();

    @Value("${app.llm.api-key:}")
    private String apiKey;

    @Value("${app.llm.base-url:https://api.deepseek.com}")
    private String baseUrl;

    @Value("${app.llm.model:deepseek-chat}")
    private String model;

    public AgentLLMClient() {
        this.restTemplate = new RestTemplateBuilder()
                .connectTimeout(Duration.ofMillis(15000))
                .readTimeout(Duration.ofMillis(30000))
                .build();
    }

    public String chat(String systemPrompt, String userMessage) {
        Map<String, Object> body = buildRequest(systemPrompt, userMessage, 0.3, 2048);
        return callApi(body);
    }

    public String chatWithHighTemp(String systemPrompt, String userMessage) {
        Map<String, Object> body = buildRequest(systemPrompt, userMessage, 0.7, 2048);
        return callApi(body);
    }

    private Map<String, Object> buildRequest(String systemPrompt, String userMessage,
                                              double temperature, int maxTokens) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("temperature", temperature);
        body.put("max_tokens", maxTokens);

        List<Map<String, String>> messages = new ArrayList<>();

        Map<String, String> sys = new LinkedHashMap<>();
        sys.put("role", "system");
        sys.put("content", systemPrompt);
        messages.add(sys);

        Map<String, String> user = new LinkedHashMap<>();
        user.put("role", "user");
        user.put("content", userMessage);
        messages.add(user);

        body.put("messages", messages);
        return body;
    }

    private String callApi(Map<String, Object> requestBody) {
        if (apiKey == null || apiKey.isBlank()) {
            log.warn("LLM API key not configured");
            throw new IllegalStateException("LLM API key not configured");
        }

        try {
            String url = baseUrl.replaceAll("/$", "") + "/v1/chat/completions";
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setBearerAuth(apiKey);

            String json = mapper.writeValueAsString(requestBody);
            HttpEntity<String> entity = new HttpEntity<>(json, headers);

            ResponseEntity<String> response = restTemplate.postForEntity(url, entity, String.class);

            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                throw new RuntimeException("LLM API returned " + response.getStatusCode());
            }

            return extractContent(response.getBody());
        } catch (Exception e) {
            log.warn("LLM API call failed: {}", e.getMessage());
            throw new RuntimeException("LLM API call failed", e);
        }
    }

    private String extractContent(String responseBody) throws Exception {
        Map<String, Object> root = mapper.readValue(responseBody, new TypeReference<>() {});
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> choices = (List<Map<String, Object>>) root.get("choices");
        if (choices == null || choices.isEmpty()) {
            throw new RuntimeException("No choices in LLM response");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> message = (Map<String, Object>) choices.get(0).get("message");
        if (message == null) return "";

        String content = (String) message.get("content");
        return content != null ? content.trim() : "";
    }
}
