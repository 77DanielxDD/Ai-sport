package com.example.aisport.agent;

import java.util.Map;

public interface AgentTool {

    String name();

    String description();

    Map<String, Object> schema();

    Map<String, Object> execute(AgentContext context, Map<String, Object> args);
}
