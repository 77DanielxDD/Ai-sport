package com.example.aisport.controller;

import com.example.aisport.agent.AgentAnswer;
import com.example.aisport.agent.AgentContext;
import com.example.aisport.agent.AgentOrchestrator;
import com.example.aisport.dto.AgentQuestionRequest;
import com.example.aisport.entity.User;
import com.example.aisport.service.UserService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.security.Principal;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

@RestController
@RequestMapping("/api/agent")
public class AgentController {

    private final AgentOrchestrator orchestrator;
    private final UserService userService;

    public AgentController(AgentOrchestrator orchestrator, UserService userService) {
        this.orchestrator = orchestrator;
        this.userService = userService;
    }

    @PostMapping("/qa")
    public ResponseEntity<?> ask(@RequestBody AgentQuestionRequest req, Principal principal) {
        if (principal == null) {
            return ResponseEntity.status(401).body(Map.of("error", "Unauthorized"));
        }
        if (req.getQuestion() == null || req.getQuestion().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "question is required"));
        }

        User user = userService.findByUsername(principal.getName())
                .orElseThrow(() -> new IllegalArgumentException("User not found"));

        AgentContext context = new AgentContext(
                principal.getName(),
                user.getId(),
                req.getVideoId(),
                req.getQuestion()
        );

        AgentAnswer answer = orchestrator.process(context);
        return ResponseEntity.ok(answer);
    }

    @PostMapping(value = "/qa/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter askStream(@RequestBody AgentQuestionRequest req, Principal principal) {
        SseEmitter emitter = new SseEmitter(180_000L);

        CompletableFuture.runAsync(() -> {
            try {
                if (principal == null) {
                    emitter.send(SseEmitter.event().name("error").data("Unauthorized"));
                    emitter.complete();
                    return;
                }

                User user = userService.findByUsername(principal.getName())
                        .orElseThrow(() -> new IllegalArgumentException("User not found"));

                AgentContext context = new AgentContext(
                        principal.getName(),
                        user.getId(),
                        req.getVideoId(),
                        req.getQuestion()
                );

                emitter.send(SseEmitter.event().name("status").data("Planning tools..."));

                AgentAnswer answer = orchestrator.process(context);

                emitter.send(SseEmitter.event().name("toolCalls").data(answer.getToolCalls()));

                for (AgentAnswer.DiagnosisItem d : answer.getDiagnosis()) {
                    emitter.send(SseEmitter.event().name("diagnosis").data(d));
                }

                for (AgentAnswer.Recommendation r : answer.getRecommendations()) {
                    emitter.send(SseEmitter.event().name("recommendation").data(r));
                }

                for (AgentAnswer.TrainingPlanItem p : answer.getTrainingPlan()) {
                    emitter.send(SseEmitter.event().name("plan").data(p));
                }

                emitter.send(SseEmitter.event().name("summary").data(answer.getSummary()));

                emitter.send(SseEmitter.event().name("references").data(answer.getReferences()));

                emitter.send(SseEmitter.event().name("done").data("[DONE]"));
                emitter.complete();
            } catch (Exception ex) {
                try {
                    emitter.send(SseEmitter.event().name("error").data(ex.getMessage()));
                } catch (Exception ignored) {}
                emitter.completeWithError(ex);
            }
        });

        return emitter;
    }
}
