package com.example.aisport.agent;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class AgentOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(AgentOrchestrator.class);

    private final AgentToolRegistry toolRegistry;
    private final AgentLLMClient llmClient;
    private final Optional<RuleBasedRouter> ruleRouter;
    private final ObjectMapper mapper = new ObjectMapper();

    public AgentOrchestrator(AgentToolRegistry toolRegistry,
                             Optional<AgentLLMClient> llmClient,
                             Optional<RuleBasedRouter> ruleRouter) {
        this.toolRegistry = toolRegistry;
        this.llmClient = llmClient.orElse(null);
        this.ruleRouter = ruleRouter;
    }

    public AgentAnswer process(AgentContext context) {
        long startTime = System.currentTimeMillis();
        log.info("Agent processing question: '{}' for user '{}'", context.getQuestion(), context.getUsername());

        // Check if LLM is available, fall back to rule-based if not
        if (llmClient == null) {
            log.info("LLM not available, using rule-based router");
            AgentAnswer answer = ruleRouter.isPresent()
                    ? ruleRouter.get().route(context)
                    : buildFallbackAnswer(context, "Agent not configured (no LLM and no rule router)");
            logAgentCall(context, "rule", null, startTime, true);
            return answer;
        }

        // Try LLM-based, catch and fall back to rule-based
        try {
            return processWithLLM(context, startTime);
        } catch (Exception e) {
            log.warn("LLM orchestration failed, falling back: {}", e.getMessage());
            if (ruleRouter.isPresent()) {
                AgentAnswer fallback = ruleRouter.get().route(context);
                logAgentCall(context, "fallback(rule)", e.getMessage(), startTime, true);
                return fallback;
            }
            AgentAnswer fallback = buildFallbackAnswer(context, "Processing error: " + e.getMessage());
            logAgentCall(context, "fallback(error)", e.getMessage(), startTime, false);
            return fallback;
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private AgentAnswer processWithLLM(AgentContext context, long startTime) {
        List<ToolCallRecord> records = new ArrayList<>();

        try {
            String toolDescriptions = toolRegistry.buildToolDescriptions();

            // Stage 1: LLM decides which tools to call
            String planPrompt = buildPlanPrompt(context, toolDescriptions);
            String planResponse = llmClient.chat(TOOL_PLAN_SYSTEM, planPrompt);

            log.debug("Stage 1 plan: {}", planResponse);

            List<Map<String, Object>> toolCalls = parseToolPlan(planResponse);

            if (toolCalls.isEmpty()) {
                log.warn("LLM returned no tool plan, attempting direct answer");
                return generateDirectAnswer(context);
            }

            // Execute tools
            Map<String, Object> toolResults = new LinkedHashMap<>();
            for (Map<String, Object> call : toolCalls) {
                String toolName = String.valueOf(call.get("tool"));
                @SuppressWarnings("unchecked")
                Map<String, Object> args = (Map<String, Object>) call.getOrDefault("args", Map.of());

                long start = System.currentTimeMillis();
                try {
                    AgentTool tool = toolRegistry.getTool(toolName);
                    Map<String, Object> result = tool.execute(context, args);
                    long elapsed = System.currentTimeMillis() - start;

                    toolResults.put(toolName, result);
                    String summary = result.containsKey("error")
                            ? toolName + " failed: " + result.get("error")
                            : toolName + " succeeded (" + result.size() + " fields)";
                    records.add(new ToolCallRecord(toolName, !result.containsKey("error"), summary, elapsed));
                    log.info("Tool {} executed in {}ms", toolName, elapsed);
                } catch (Exception e) {
                    long elapsed = System.currentTimeMillis() - start;
                    toolResults.put(toolName, Map.of("error", e.getMessage()));
                    records.add(new ToolCallRecord(toolName, false, toolName + " error: " + e.getMessage(), elapsed));
                    log.warn("Tool {} failed: {}", toolName, e.getMessage());
                }
            }

            // Stage 2: LLM generates structured answer from tool results
            String answerPrompt = buildAnswerPrompt(context, toolResults);
            String answerResponse = llmClient.chatWithHighTemp(ANSWER_SYSTEM, answerPrompt);

            log.debug("Stage 2 raw response (first 200 chars): {}",
                    answerResponse.substring(0, Math.min(200, answerResponse.length())));

            AgentAnswer answer = parseAnswer(answerResponse);
            answer.setToolCalls(records);

            // Attach knowledge references
            Object knowledgeResult = toolResults.get("search_knowledge");
            if (knowledgeResult instanceof Map) {
                Map<String, Object> km = (Map<String, Object>) knowledgeResult;
                Object results = km.get("results");
                if (results instanceof List) {
                    List<?> list = (List<?>) results;
                    for (Object item : list) {
                        if (item instanceof Map) {
                            Map<String, Object> doc = (Map<String, Object>) item;
                            AgentAnswer.ReferenceItem ref = new AgentAnswer.ReferenceItem();
                            ref.setType("knowledge");
                            ref.setTitle(String.valueOf(doc.getOrDefault("title", "")));
                            Object contentObj = doc.get("content");
                            String content = contentObj != null ? contentObj.toString() : "";
                            ref.setSnippet(content.length() > 120 ? content.substring(0, 120) : content);
                            answer.getReferences().add(ref);
                        }
                    }
                }
            }

            return answer;

        } catch (Exception e) {
            log.error("Agent orchestration failed: {}", e.getMessage(), e);
            throw new RuntimeException("Agent processing error: " + e.getMessage(), e);
        }
    }

    private AgentAnswer generateDirectAnswer(AgentContext context) {
        try {
            String directPrompt = "User question: " + context.getQuestion() + "\n\n"
                    + "Generate a training advice response in JSON format matching the schema.";
            String response = llmClient.chat(DIRECT_ANSWER_SYSTEM, directPrompt);
            AgentAnswer answer = parseAnswer(response);
            answer.setToolCalls(List.of());
            return answer;
        } catch (Exception e) {
            throw new RuntimeException("Could not generate answer: " + e.getMessage(), e);
        }
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> parseToolPlan(String response) {
        String json = extractJson(response);
        if (json == null) return List.of();

        try {
            Object parsed = mapper.readValue(json, new TypeReference<Object>() {});
            if (parsed instanceof List) {
                List<?> list = (List<?>) parsed;
                List<Map<String, Object>> result = new ArrayList<>();
                for (Object item : list) {
                    if (item instanceof Map) {
                        Map<String, Object> m = (Map<String, Object>) item;
                        Map<String, Object> call = new LinkedHashMap<>();
                        call.put("tool", String.valueOf(m.get("tool")));
                        Object args = m.get("args");
                        if (args instanceof Map) {
                            Map<String, Object> argMap = (Map<String, Object>) args;
                            Map<String, Object> safeArgs = new LinkedHashMap<>();
                            for (Map.Entry<String, Object> e : argMap.entrySet()) {
                                safeArgs.put(e.getKey(), e.getValue());
                            }
                            call.put("args", safeArgs);
                        } else {
                            call.put("args", Map.of());
                        }
                        result.add(call);
                    }
                }
                return result;
            } else if (parsed instanceof Map) {
                Map<String, Object> single = (Map<String, Object>) parsed;
                Map<String, Object> call = new LinkedHashMap<>();
                call.put("tool", String.valueOf(single.get("tool")));
                Object args = single.get("args");
                if (args instanceof Map) {
                    Map<String, Object> argMap = (Map<String, Object>) args;
                    Map<String, Object> safeArgs = new LinkedHashMap<>();
                    for (Map.Entry<String, Object> e : argMap.entrySet()) {
                        safeArgs.put(e.getKey(), e.getValue());
                    }
                    call.put("args", safeArgs);
                } else {
                    call.put("args", Map.of());
                }
                return List.of(call);
            }
        } catch (Exception e) {
            log.warn("Failed to parse tool plan JSON: {}", e.getMessage());
        }
        return List.of();
    }

    private AgentAnswer parseAnswer(String response) {
        String json = extractJson(response);
        if (json == null) {
            throw new RuntimeException("No JSON found in LLM response");
        }

        // Try direct parse
        try {
            return mapper.readValue(json, AgentAnswer.class);
        } catch (Exception e) {
            log.warn("Initial parse failed, attempting repair: {}", e.getMessage());
        }

        // Repair: try wrapping in a repair prompt
        try {
            String repairPrompt = "Fix this JSON to match the required schema (summary, diagnosis[], recommendations[], trainingPlan[]):\n" + json;
            String repairResponse = llmClient.chat(REPAIR_SYSTEM, repairPrompt);
            String repairedJson = extractJson(repairResponse);
            if (repairedJson != null) {
                return mapper.readValue(repairedJson, AgentAnswer.class);
            }
        } catch (Exception e) {
            log.warn("Repair attempt failed: {}", e.getMessage());
        }

        throw new RuntimeException("Failed to parse AgentAnswer from LLM response");
    }

    private String extractJson(String text) {
        if (text == null) return null;

        // Try finding JSON block with ```json markers
        int start = text.indexOf("```json");
        if (start >= 0) {
            start += 7;
            int end = text.indexOf("```", start);
            if (end > start) return text.substring(start, end).trim();
        }

        // Try ``` markers
        start = text.indexOf("```");
        if (start >= 0) {
            start += 3;
            int end = text.indexOf("```", start);
            if (end > start) return text.substring(start, end).trim();
        }

        // Try finding first { or [ directly
        int brace = text.indexOf('{');
        int bracket = text.indexOf('[');
        int jsonStart = -1;
        if (brace >= 0 && bracket >= 0) jsonStart = Math.min(brace, bracket);
        else if (brace >= 0) jsonStart = brace;
        else if (bracket >= 0) jsonStart = bracket;

        if (jsonStart >= 0) {
            // Find matching closing bracket
            char openChar = text.charAt(jsonStart);
            char closeChar = openChar == '{' ? '}' : ']';
            int depth = 0;
            boolean inString = false;
            for (int i = jsonStart; i < text.length(); i++) {
                char c = text.charAt(i);
                if (c == '"' && (i == 0 || text.charAt(i - 1) != '\\')) inString = !inString;
                if (!inString) {
                    if (c == openChar) depth++;
                    if (c == closeChar) {
                        depth--;
                        if (depth == 0) {
                            return text.substring(jsonStart, i + 1);
                        }
                    }
                }
            }
        }

        return null;
    }

    private AgentAnswer buildFallbackAnswer(AgentContext context, String reason) {
        AgentAnswer fallback = new AgentAnswer();
        fallback.setSummary("系统暂时无法生成个性化分析。 " + reason);

        AgentAnswer.Recommendation rec = new AgentAnswer.Recommendation();
        rec.setTitle("基础训练建议");
        rec.setDetail("请先完成一次视频分析，获得个性化数据后再提问。你也可以参考健身知识库中的标准动作指南。");
        rec.setPriority("medium");
        fallback.setRecommendations(List.of(rec));

        AgentAnswer.TrainingPlanItem plan = new AgentAnswer.TrainingPlanItem();
        plan.setDay("通用方案");
        plan.setContent("以技术优先，降低负重，每次训练录制视频进行对比");
        plan.setFocus("动作质量和稳定性");
        fallback.setTrainingPlan(List.of(plan));

        ToolCallRecord record = new ToolCallRecord("agent", false, "Fallback: " + reason, 0);
        fallback.setToolCalls(List.of(record));
        return fallback;
    }

    private String buildPlanPrompt(AgentContext context, String toolDescriptions) {
        StringBuilder sb = new StringBuilder();
        sb.append("## User Profile\n");
        sb.append("Username: ").append(context.getUsername()).append("\n");
        if (context.getUserId() != null) {
            sb.append("UserId: ").append(context.getUserId()).append("\n");
        }
        if (context.getFocusVideoId() != null) {
            sb.append("Focus VideoId: ").append(context.getFocusVideoId())
              .append(" (user is asking about this specific session)\n");
        }
        sb.append("\n## Tools Available\n");
        sb.append(toolDescriptions);
        sb.append("\n## Instructions\n");
        sb.append("Analyze the user's question and decide which tools to call. ");
        sb.append("Return a JSON array of tool calls. Each call has 'tool' and 'args' fields.\n");
        sb.append("Example: [{\"tool\": \"get_training_history\", \"args\": {\"limit\": 5}}]\n");
        sb.append("If knowledge is needed, include search_knowledge with relevant query.\n");
        sb.append("If user profile context is needed, include get_user_memory.\n");
        sb.append("Only call tools that are necessary. Max 3 tool calls.\n\n");
        sb.append("User Question: ").append(context.getQuestion());
        return sb.toString();
    }

    private String buildAnswerPrompt(AgentContext context, Map<String, Object> toolResults) {
        StringBuilder sb = new StringBuilder();
        sb.append("## Original Question\n").append(context.getQuestion()).append("\n\n");
        sb.append("## Tool Results\n");
        try {
            sb.append(mapper.writerWithDefaultPrettyPrinter().writeValueAsString(toolResults));
        } catch (Exception e) {
            sb.append(toolResults);
        }
        sb.append("\n\n## Instructions\n");
        sb.append("Based on the tool results, generate a comprehensive training answer in the exact JSON schema:\n");
        sb.append("""
        {
          "summary": "One-sentence conclusion in Chinese",
          "diagnosis": [{"issue": "problem description", "evidence": "data-backed evidence", "severity": "high/medium/low"}],
          "recommendations": [{"title": "action title", "detail": "detailed advice", "priority": "high/medium/low"}],
          "trainingPlan": [{"day": "Day range", "content": "what to do", "focus": "training focus"}]
        }
        """);
        sb.append("Respond in Chinese unless the question is in English.\n");
        sb.append("Be specific and reference actual scores/data from tool results.\n");
        sb.append("If tool results contain errors, acknowledge limitations gracefully.\n");
        return sb.toString();
    }

    private void logAgentCall(AgentContext context, String mode, String error, long startTime, boolean success) {
        long elapsed = System.currentTimeMillis() - startTime;
        log.info("AgentCall | user={} | mode={} | question={} | success={} | error={} | durationMs={}",
                context.getUsername(), mode,
                context.getQuestion().length() > 60 ? context.getQuestion().substring(0, 60) + "..." : context.getQuestion(),
                success, error != null ? error : "none", elapsed);
    }

    // ---- System prompts ----

    private static final String TOOL_PLAN_SYSTEM = """
            You are a fitness training analysis agent. Your job is to select the right tools to answer the user's training question.
            Analyze what information is needed and pick tools accordingly. Return only valid JSON, no explanation.
            """;

    private static final String ANSWER_SYSTEM = """
            You are an expert fitness coach analyzing training data. Generate structured, actionable advice in Chinese.
            Be specific, reference actual data, and focus on safety and proper form. Return only valid JSON matching the schema.
            """;

    private static final String DIRECT_ANSWER_SYSTEM = """
            You are an expert fitness coach. Generate a structured training answer in JSON format.
            Include summary, diagnosis (issues with evidence), recommendations, and training plan.
            Respond in Chinese. Return only valid JSON.
            """;

    private static final String REPAIR_SYSTEM = """
            Fix the JSON to match this schema exactly: {summary: string, diagnosis: [{issue, evidence, severity}],
            recommendations: [{title, detail, priority}], trainingPlan: [{day, content, focus}]}.
            Return only the fixed JSON, no explanation.
            """;
}
