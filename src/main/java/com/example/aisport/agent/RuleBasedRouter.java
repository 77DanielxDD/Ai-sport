package com.example.aisport.agent;

import com.example.aisport.agent.tools.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;

@Component
@SuppressWarnings({"unchecked", "rawtypes"})
public class RuleBasedRouter {

    private static final Logger log = LoggerFactory.getLogger(RuleBasedRouter.class);

    private final AgentToolRegistry toolRegistry;

    public RuleBasedRouter(AgentToolRegistry toolRegistry) {
        this.toolRegistry = toolRegistry;
    }

    public AgentAnswer route(AgentContext context) {
        log.info("Rule-based routing for: '{}'", context.getQuestion());
        String q = context.getQuestion().toLowerCase(Locale.ROOT);
        List<ToolCallRecord> records = new ArrayList<>();
        Map<String, Object> results = new LinkedHashMap<>();

        // Determine which tools to call based on keywords
        Set<String> toolsToCall = decideTools(q);

        for (String toolName : toolsToCall) {
            Map<String, Object> args = buildArgs(toolName, context);
            long start = System.currentTimeMillis();
            try {
                AgentTool tool = toolRegistry.getTool(toolName);
                Map<String, Object> result = tool.execute(context, args);
                long elapsed = System.currentTimeMillis() - start;
                results.put(toolName, result);
                boolean success = !result.containsKey("error");
                records.add(new ToolCallRecord(toolName, success,
                        success ? toolName + " succeeded" : toolName + " failed: " + result.get("error"), elapsed));
            } catch (Exception e) {
                long elapsed = System.currentTimeMillis() - start;
                results.put(toolName, Map.of("error", e.getMessage()));
                records.add(new ToolCallRecord(toolName, false, toolName + " error: " + e.getMessage(), elapsed));
            }
        }

        return buildAnswer(context, results, records);
    }

    private Set<String> decideTools(String q) {
        Set<String> tools = new LinkedHashSet<>();

        boolean hasReportKeyword = containsAny(q, "这次", "本次", "报告", "视频", "this", "current", "report");
        boolean hasHistoryKeyword = containsAny(q, "最近", "历史", "趋势", "进步", "变化", "recent", "history", "trend", "improve");
        boolean hasPlanKeyword = containsAny(q, "怎么练", "计划", "改善", "纠正", "错误", "plan", "improve", "correct", "fix", "how to");
        boolean hasProfileKeyword = containsAny(q, "我", "问题", "短板", "弱项", "my", "weak", "problem");

        if (hasReportKeyword) {
            tools.add("get_video_report");
        }
        if (hasHistoryKeyword) {
            tools.add("get_training_history");
            tools.add("get_score_trend");
        }
        if (hasPlanKeyword) {
            tools.add("search_knowledge");
        }
        if (hasProfileKeyword) {
            tools.add("get_user_memory");
        }

        // Default: history + knowledge
        if (tools.isEmpty()) {
            tools.add("get_training_history");
            tools.add("search_knowledge");
        }

        // Always try to add user memory for context
        tools.add("get_user_memory");

        return tools;
    }

    private Map<String, Object> buildArgs(String toolName, AgentContext context) {
        return switch (toolName) {
            case "get_video_report" -> {
                Map<String, Object> args = new LinkedHashMap<>();
                if (context.getFocusVideoId() != null) {
                    args.put("videoId", context.getFocusVideoId());
                }
                yield args;
            }
            case "get_training_history" -> {
                Map<String, Object> args = new LinkedHashMap<>();
                args.put("limit", 8);
                yield args;
            }
            case "get_score_trend" -> {
                Map<String, Object> args = new LinkedHashMap<>();
                args.put("days", 30);
                yield args;
            }
            case "search_knowledge" -> {
                Map<String, Object> args = new LinkedHashMap<>();
                args.put("query", context.getQuestion());
                args.put("topK", 3);
                yield args;
            }
            default -> Map.of();
        };
    }

    private AgentAnswer buildAnswer(AgentContext context, Map<String, Object> results, List<ToolCallRecord> records) {
        AgentAnswer answer = new AgentAnswer();
        answer.setToolCalls(records);

        // Build summary
        StringBuilder summary = new StringBuilder();
        Object history = results.get("get_training_history");
        Object report = results.get("get_video_report");
        Object knowledge = results.get("search_knowledge");
        Object memory = results.get("get_user_memory");

        if (report instanceof Map rm && !rm.containsKey("error")) {
            @SuppressWarnings("unchecked")
            Map<String, Object> reportMap = (Map<String, Object>) rm;
            Object fb = reportMap.get("overallFeedback");
            if (fb != null) summary.append("当前训练分析：").append(fb).append("。");
        }

        if (history instanceof Map hm && !hm.containsKey("error")) {
            @SuppressWarnings("unchecked")
            Map<String, Object> histMap = (Map<String, Object>) hm;
            Object avg = histMap.get("averageScore");
            Object total = histMap.get("totalCompleted");
            if (avg != null) {
                summary.append("近期").append(total).append("次训练平均分").append(avg).append("。");
            }
        }

        if (memory instanceof Map mm && !mm.containsKey("error")) {
            @SuppressWarnings("unchecked")
            Map<String, Object> memMap = (Map<String, Object>) mm;
            Object weak = memMap.get("weakExerciseTypes");
            Object mistakes = memMap.get("commonMistakes");
            if (weak != null && !String.valueOf(weak).isBlank()) {
                summary.append("薄弱动作：").append(weak).append("。");
            }
            if (mistakes != null && !String.valueOf(mistakes).isBlank()) {
                summary.append("常见问题：").append(mistakes).append("。");
            }
        }

        if (summary.isEmpty()) {
            summary.append("请先完成至少一次视频分析，获得个性化数据后提问更有效。");
        }
        answer.setSummary(summary.toString());

        // Build diagnosis from report + memory
        List<AgentAnswer.DiagnosisItem> diagnoses = new ArrayList<>();
        if (report instanceof Map rm && !rm.containsKey("error")) {
            @SuppressWarnings("unchecked")
            Map<String, Object> reportMap = (Map<String, Object>) rm;
            Object tips = reportMap.get("tips");
            if (tips instanceof List tipList) {
                for (int i = 0; i < Math.min(tipList.size(), 3); i++) {
                    Object t = tipList.get(i);
                    if (t instanceof Map tipMap) {
                        AgentAnswer.DiagnosisItem d = new AgentAnswer.DiagnosisItem();
                        d.setIssue("第" + tipMap.get("repIndex") + "次动作需要关注");
                        Object tipText = tipMap.get("tipCn");
                        if (tipText == null) tipText = tipMap.get("tip");
                        d.setEvidence(tipText != null ? String.valueOf(tipText) : "角度数据待分析");
                        d.setSeverity("medium");
                        diagnoses.add(d);
                    }
                }
            }
        }

        if (memory instanceof Map mm && !mm.containsKey("error")) {
            @SuppressWarnings("unchecked")
            Map<String, Object> memMap = (Map<String, Object>) mm;
            Object weak = memMap.get("weakExerciseTypes");
            if (weak != null && !String.valueOf(weak).isBlank()) {
                AgentAnswer.DiagnosisItem d = new AgentAnswer.DiagnosisItem();
                d.setIssue("薄弱动作类型");
                d.setEvidence("以下动作评分偏低：" + weak);
                d.setSeverity("high");
                diagnoses.add(d);
            }
            Object commonMistakes = memMap.get("commonMistakes");
            if (commonMistakes != null && !String.valueOf(commonMistakes).isBlank()) {
                AgentAnswer.DiagnosisItem d = new AgentAnswer.DiagnosisItem();
                d.setIssue("常见错误模式");
                d.setEvidence(String.valueOf(commonMistakes));
                d.setSeverity("medium");
                diagnoses.add(d);
            }
        }

        answer.setDiagnosis(diagnoses);

        // Build recommendations
        List<AgentAnswer.Recommendation> recs = new ArrayList<>();
        if (report instanceof Map rm && !rm.containsKey("error")) {
            @SuppressWarnings("unchecked")
            Map<String, Object> reportMap = (Map<String, Object>) rm;
            Object suggestions = reportMap.get("suggestions");
            if (suggestions instanceof List sugList) {
                for (Object s : sugList) {
                    if (s instanceof String str) {
                        AgentAnswer.Recommendation r = new AgentAnswer.Recommendation();
                        r.setTitle("针对性建议");
                        r.setDetail(str);
                        r.setPriority("medium");
                        recs.add(r);
                    }
                }
            }
            Object score = reportMap.get("scoreBreakdown");
            if (score instanceof Map sm) {
                Object formScore = sm.get("formScore");
                Object rhythmScore = sm.get("rhythmScore");
                if (formScore instanceof Number fn && fn.doubleValue() < 60) {
                    AgentAnswer.Recommendation r = new AgentAnswer.Recommendation();
                    r.setTitle("优先改善动作幅度");
                    r.setDetail("幅度评分较低（" + Math.round(fn.doubleValue()) + "分），建议降低负重，优先保证动作幅度");
                    r.setPriority("high");
                    recs.add(r);
                }
                if (rhythmScore instanceof Number rn && rn.doubleValue() < 60) {
                    AgentAnswer.Recommendation r = new AgentAnswer.Recommendation();
                    r.setTitle("改善动作节奏");
                    r.setDetail("节奏评分较低（" + Math.round(rn.doubleValue()) + "分），建议使用慢速离心控制");
                    r.setPriority("high");
                    recs.add(r);
                }
            }
        }

        if (knowledge instanceof Map km && !km.containsKey("error")) {
            AgentAnswer.Recommendation r = new AgentAnswer.Recommendation();
            r.setTitle("参考知识库建议");
            r.setDetail("已检索相关知识库内容，请查看「参考来源」部分获取详细建议");
            r.setPriority("low");
            recs.add(r);
        }

        if (recs.isEmpty()) {
            AgentAnswer.Recommendation r = new AgentAnswer.Recommendation();
            r.setTitle("开始你的第一次分析");
            r.setDetail("上传训练视频获取个性化动作分析报告，系统将根据你的数据生成针对性建议");
            r.setPriority("medium");
            recs.add(r);
        }
        answer.setRecommendations(recs);

        // Build training plan
        List<AgentAnswer.TrainingPlanItem> plan = buildDefaultPlan(results);
        answer.setTrainingPlan(plan);

        // Build references from knowledge search
        if (knowledge instanceof Map) {
            Map<String, Object> knowledgeMap = (Map<String, Object>) knowledge;
            Object results2 = knowledgeMap.get("results");
            if (results2 instanceof List) {
                List refList = (List) results2;
                List<AgentAnswer.ReferenceItem> refs = new ArrayList<>();
                for (Object item : refList) {
                    if (item instanceof Map) {
                        Map<String, Object> doc = (Map<String, Object>) item;
                        AgentAnswer.ReferenceItem ref = new AgentAnswer.ReferenceItem();
                        ref.setType("knowledge");
                        ref.setTitle(String.valueOf(doc.getOrDefault("title", "")));
                        Object contentObj = doc.get("content");
                        String content = contentObj != null ? contentObj.toString() : "";
                        ref.setSnippet(content.length() > 100 ? content.substring(0, 100) + "..." : content);
                        refs.add(ref);
                    }
                }
                answer.setReferences(refs);
            }
        }

        return answer;
    }

    @SuppressWarnings("unchecked")
    private List<AgentAnswer.TrainingPlanItem> buildDefaultPlan(Map<String, Object> results) {
        List<AgentAnswer.TrainingPlanItem> plan = new ArrayList<>();

        Object history = results.get("get_training_history");
        Object report = results.get("get_video_report");

        boolean hasData = false;
        double avgScore = 0;
        if (report instanceof Map rm && !rm.containsKey("error")) {
            Object score = ((Map<String, Object>) rm).get("scoreBreakdown");
            if (score instanceof Map sm) {
                Object fs = ((Map<String, Object>) sm).get("finalScore");
                if (fs instanceof Number n) {
                    avgScore = n.doubleValue();
                    hasData = true;
                }
            }
        }

        if (!hasData && history instanceof Map hm && !hm.containsKey("error")) {
            Object avg = ((Map<String, Object>) hm).get("averageScore");
            if (avg instanceof Number n) {
                avgScore = n.doubleValue();
                hasData = true;
            }
        }

        if (hasData && avgScore < 60) {
            plan.add(item("Day 1-2", "技术组训练，50%负荷，慢速离心", "动作质量优先"));
            plan.add(item("Day 3-4", "保持60%负荷，加入底部停顿1秒", "控制稳定性"));
            plan.add(item("Day 5-6", "恢复至70%负荷，录制视频对比", "验证改进效果"));
            plan.add(item("Day 7", "减量恢复，拉伸放松，技术回顾", "恢复与复盘"));
        } else if (hasData && avgScore < 80) {
            plan.add(item("Day 1-2", "60-70%负荷，专注节奏控制", "节奏改善"));
            plan.add(item("Day 3-4", "保持负荷，每组加1次", "逐步增量"));
            plan.add(item("Day 5-6", "提高至75%负荷，加入一组技术组", "强度与技术"));
            plan.add(item("Day 7", "中等负荷，录制视频做对比分析", "效果评估"));
        } else {
            plan.add(item("Day 1-2", "技术组训练，50-60%负荷，慢速离心3秒", "动作幅度与节奏"));
            plan.add(item("Day 3-4", "恢复至65-75%负荷，保持稳定幅度", "稳步提升"));
            plan.add(item("Day 5-6", "维持负荷，增加一组技术组", "巩固与增量"));
            plan.add(item("Day 7", "减量 + 灵活性训练 + 本周回顾", "恢复与复盘"));
        }

        return plan;
    }

    private AgentAnswer.TrainingPlanItem item(String day, String content, String focus) {
        AgentAnswer.TrainingPlanItem p = new AgentAnswer.TrainingPlanItem();
        p.setDay(day);
        p.setContent(content);
        p.setFocus(focus);
        return p;
    }

    private boolean containsAny(String text, String... keywords) {
        for (String kw : keywords) {
            if (text.contains(kw)) return true;
        }
        return false;
    }
}
