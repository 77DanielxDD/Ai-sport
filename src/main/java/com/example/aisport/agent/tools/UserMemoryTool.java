package com.example.aisport.agent.tools;

import com.example.aisport.agent.AgentContext;
import com.example.aisport.agent.AgentTool;
import com.example.aisport.entity.User;
import com.example.aisport.memory.UserMemoryService;
import com.example.aisport.service.UserService;
import org.springframework.stereotype.Component;

import java.util.*;

@Component
public class UserMemoryTool implements AgentTool {

    private final UserMemoryService userMemoryService;
    private final UserService userService;

    public UserMemoryTool(UserMemoryService userMemoryService, UserService userService) {
        this.userMemoryService = userMemoryService;
        this.userService = userService;
    }

    @Override
    public String name() { return "get_user_memory"; }

    @Override
    public String description() {
        return "Get user's long-term training profile including total sessions, average score, "
             + "score trend (improving/declining/stable), weak exercise types, and common mistakes. "
             + "Use this to personalize answers based on user's overall training history and patterns.";
    }

    @Override
    public Map<String, Object> schema() {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("type", "object");
        s.put("description", "Get user memory/profile. No arguments needed - pulls current user automatically.");
        Map<String, Object> props = new LinkedHashMap<>();
        s.put("properties", props);
        return s;
    }

    @Override
    public Map<String, Object> execute(AgentContext context, Map<String, Object> args) {
        Optional<User> userOpt = userService.findByUsername(context.getUsername());
        if (userOpt.isEmpty()) {
            return Map.of("error", "User not found");
        }

        Map<String, Object> profile = userMemoryService.getProfileMap(userOpt.get().getId());
        if (profile == null || profile.isEmpty()) {
            return Map.of("message", "No user profile yet. User may be new or profile not yet built.");
        }

        Map<String, Object> result = new LinkedHashMap<>(profile);
        result.put("username", context.getUsername());
        return result;
    }
}
