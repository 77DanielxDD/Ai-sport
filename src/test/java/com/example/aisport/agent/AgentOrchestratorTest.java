package com.example.aisport.agent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(MockitoExtension.class)
class AgentOrchestratorTest {

    private AgentToolRegistry makeRegistry(AgentTool... extra) {
        List<AgentTool> tools = new ArrayList<>();
        tools.add(new StubTool("get_training_history", "Get training history"));
        tools.add(new StubTool("get_video_report", "Get video report"));
        tools.add(new StubTool("get_score_trend", "Get score trends"));
        tools.add(new StubTool("search_knowledge", "Search knowledge"));
        tools.add(new StubTool("get_user_memory", "Get user memory"));
        if (extra.length > 0) Collections.addAll(tools, extra);
        return new AgentToolRegistry(tools);
    }

    @Test
    void shouldUseRuleRouterWhenNoLlmClient() {
        AgentToolRegistry reg = makeRegistry();
        RuleBasedRouter ruleRouter = new RuleBasedRouter(reg);
        AgentOrchestrator orchestrator = new AgentOrchestrator(reg, Optional.empty(), Optional.of(ruleRouter));

        AgentContext ctx = new AgentContext("testuser", 1L, null, "最近训练怎么样？");
        AgentAnswer answer = orchestrator.process(ctx);

        assertNotNull(answer);
        assertNotNull(answer.getSummary());
        assertFalse(answer.getToolCalls().isEmpty());
    }

    @Test
    void shouldBuildFallbackWhenNoRouterAndNoLlm() {
        AgentToolRegistry reg = makeRegistry();
        AgentOrchestrator orchestrator = new AgentOrchestrator(reg, Optional.empty(), Optional.empty());

        AgentContext ctx = new AgentContext("testuser", 1L, null, "test question");
        AgentAnswer answer = orchestrator.process(ctx);

        assertNotNull(answer);
        assertNotNull(answer.getSummary());
        assertTrue(answer.getSummary().contains("Agent not configured"));
    }

    @Test
    void registryShouldRegisterAllTools() {
        AgentToolRegistry reg = makeRegistry();
        assertEquals(5, reg.getAllTools().size());
        assertNotNull(reg.getTool("get_training_history"));
        assertNotNull(reg.getTool("get_video_report"));
        assertNotNull(reg.getTool("get_score_trend"));
        assertNotNull(reg.getTool("search_knowledge"));
        assertNotNull(reg.getTool("get_user_memory"));
    }

    @Test
    void registryShouldThrowOnUnknownTool() {
        AgentToolRegistry reg = makeRegistry();
        assertThrows(IllegalArgumentException.class, () -> reg.getTool("nonexistent_tool"));
    }

    @Test
    void toolDescriptionsShouldContainAllTools() {
        AgentToolRegistry reg = makeRegistry();
        String desc = reg.buildToolDescriptions();
        assertTrue(desc.contains("get_training_history"), desc);
        assertTrue(desc.contains("get_video_report"), desc);
    }

    @Test
    void contextShouldStoreAndReturnAttributes() {
        AgentContext ctx = new AgentContext("user", 1L, 42L, "how to improve?");
        assertEquals("user", ctx.getUsername());
        assertEquals(1L, ctx.getUserId());
        assertEquals(42L, ctx.getFocusVideoId());
        assertEquals("how to improve?", ctx.getQuestion());
    }

    @Test
    void contextShouldSupportCustomAttributes() {
        AgentContext ctx = new AgentContext("user", 1L, null, "q");
        ctx.setAttribute("key", "value");
        assertEquals("value", ctx.getAttribute("key"));
    }

    static class StubTool implements AgentTool {
        private final String n, d;
        StubTool(String n, String d) { this.n = n; this.d = d; }
        public String name() { return n; }
        public String description() { return d; }
        public Map<String, Object> schema() { return Map.of("type", "object", "properties", Map.of()); }
        public Map<String, Object> execute(AgentContext ctx, Map<String, Object> args) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put(n + "_result", "ok");
            return r;
        }
    }
}
