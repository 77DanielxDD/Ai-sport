package com.example.aisport.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
public class AgentToolRegistry {

    private static final Logger log = LoggerFactory.getLogger(AgentToolRegistry.class);

    private final Map<String, AgentTool> tools = new HashMap<>();

    public AgentToolRegistry(List<AgentTool> toolList) {
        for (AgentTool tool : toolList) {
            tools.put(tool.name(), tool);
            log.info("Registered agent tool: {}", tool.name());
        }
    }

    public AgentTool getTool(String name) {
        AgentTool tool = tools.get(name);
        if (tool == null) {
            throw new IllegalArgumentException("Unknown tool: " + name);
        }
        return tool;
    }

    public List<AgentTool> getAllTools() {
        return List.copyOf(tools.values());
    }

    public String buildToolDescriptions() {
        StringBuilder sb = new StringBuilder();
        sb.append("Available tools:\n\n");
        for (AgentTool tool : tools.values()) {
            sb.append("## ").append(tool.name()).append("\n");
            sb.append(tool.description()).append("\n");
            sb.append("Schema: ").append(formatJson(tool.schema())).append("\n\n");
        }
        return sb.toString();
    }

    private String formatJson(Map<String, Object> map) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper()
                    .writerWithDefaultPrettyPrinter()
                    .writeValueAsString(map);
        } catch (Exception e) {
            return map.toString();
        }
    }
}
