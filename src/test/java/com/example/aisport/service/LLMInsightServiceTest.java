package com.example.aisport.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.*;
import org.springframework.web.client.RestTemplate;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class LLMInsightServiceTest {

    @Mock
    private RestTemplate restTemplate;

    @Test
    void shouldReturnNullWhenApiKeyBlank() {
        LLMInsightService svc = new LLMInsightService("", "https://api.deepseek.com", "deepseek-chat", restTemplate);
        assertNull(svc.generateInsight(Map.of("exerciseType", "PUSHUP")));
    }

    @Test
    void shouldReturnNullWhenApiKeyNull() {
        LLMInsightService svc = new LLMInsightService(null, "https://api.deepseek.com", "deepseek-chat", restTemplate);
        assertNull(svc.generateInsight(Map.of("exerciseType", "SQUAT")));
    }

    @Test
    void shouldReturnMapOnSuccessfulCall() {
        String mockBody = "{\"choices\":[{\"message\":{\"content\":\"{\\\"overallFeedback\\\":\\\"非常棒\\\",\\\"suggestions\\\":[\\\"保持稳定\\\"],\\\"repTipsCn\\\":[\\\"第1次：完美\\\"]}\"}}]}";
        ResponseEntity<String> entity = new ResponseEntity<>(mockBody, HttpStatus.OK);
        when(restTemplate.postForEntity(anyString(), any(HttpEntity.class), eq(String.class))).thenReturn(entity);

        LLMInsightService svc = new LLMInsightService("sk-test", "https://api.deepseek.com", "deepseek-chat", restTemplate);
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("exerciseType", "PUSHUP");
        input.put("finalScore", 75.0);
        input.put("level", "良好");
        input.put("formScore", 72.0);
        input.put("repCount", 10);
        input.put("avgMinAngle", 78.0);
        input.put("targetAngle", 80.0);
        input.put("tips", List.of());

        Map<String, Object> result = svc.generateInsight(input);
        assertNotNull(result);
        assertEquals("非常棒", result.get("overallFeedback"));
        assertEquals("保持稳定", ((List<?>) result.get("suggestions")).get(0));
        assertEquals("第1次：完美", ((List<?>) result.get("repTipsCn")).get(0));
    }

    @Test
    void shouldReturnNullOnNon2xxResponse() {
        ResponseEntity<String> entity = new ResponseEntity<>("Unauthorized", HttpStatus.UNAUTHORIZED);
        when(restTemplate.postForEntity(anyString(), any(HttpEntity.class), eq(String.class))).thenReturn(entity);

        LLMInsightService svc = new LLMInsightService("sk-test", "https://api.deepseek.com", "deepseek-chat", restTemplate);
        assertNull(svc.generateInsight(Map.of("exerciseType", "PUSHUP")));
    }

    @Test
    void shouldReturnNullOnMalformedJson() {
        ResponseEntity<String> entity = new ResponseEntity<>("not valid json at all", HttpStatus.OK);
        when(restTemplate.postForEntity(anyString(), any(HttpEntity.class), eq(String.class))).thenReturn(entity);

        LLMInsightService svc = new LLMInsightService("sk-test", "https://api.deepseek.com", "deepseek-chat", restTemplate);
        assertNull(svc.generateInsight(Map.of("exerciseType", "PUSHUP")));
    }

    @Test
    void shouldStripMarkdownCodeFence() {
        String mockBody = "{\"choices\":[{\"message\":{\"content\":\"```json\\n{\\\"overallFeedback\\\":\\\"OK\\\",\\\"suggestions\\\":[],\\\"repTipsCn\\\":[]}\\n```\"}}]}";
        ResponseEntity<String> entity = new ResponseEntity<>(mockBody, HttpStatus.OK);
        when(restTemplate.postForEntity(anyString(), any(HttpEntity.class), eq(String.class))).thenReturn(entity);

        LLMInsightService svc = new LLMInsightService("sk-test", "https://api.deepseek.com", "deepseek-chat", restTemplate);
        Map<String, Object> result = svc.generateInsight(Map.of("exerciseType", "PUSHUP"));
        assertNotNull(result);
        assertEquals("OK", result.get("overallFeedback"));
    }

    @Test
    void shouldHandleEmptyChoices() {
        String mockBody = "{\"choices\":[]}";
        ResponseEntity<String> entity = new ResponseEntity<>(mockBody, HttpStatus.OK);
        when(restTemplate.postForEntity(anyString(), any(HttpEntity.class), eq(String.class))).thenReturn(entity);

        LLMInsightService svc = new LLMInsightService("sk-test", "https://api.deepseek.com", "deepseek-chat", restTemplate);
        assertNull(svc.generateInsight(Map.of("exerciseType", "PUSHUP")));
    }
}
