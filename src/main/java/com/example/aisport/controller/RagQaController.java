package com.example.aisport.controller;

import com.example.aisport.dto.RagQuestionRequest;
import com.example.aisport.service.RagQaService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.security.Principal;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

@RestController
@RequestMapping("/api/rag")
public class RagQaController {

    private final RagQaService ragQaService;

    public RagQaController(RagQaService ragQaService) {
        this.ragQaService = ragQaService;
    }

    @PostMapping("/qa")
    public ResponseEntity<?> ask(@RequestBody RagQuestionRequest req, Principal principal) {
        if (principal == null) {
            return ResponseEntity.status(401).body(Map.of("error", "Unauthorized"));
        }
        String answer = ragQaService.buildPersonalizedAnswer(principal.getName(), req.getVideoId(), req.getQuestion());
        return ResponseEntity.ok(Map.of(
                "question", req.getQuestion(),
                "answer", answer
        ));
    }

    @PostMapping(value = "/qa/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter askStream(@RequestBody RagQuestionRequest req, Principal principal) {
        SseEmitter emitter = new SseEmitter(180_000L);

        CompletableFuture.runAsync(() -> {
            try {
                if (principal == null) {
                    emitter.send(SseEmitter.event().name("error").data("Unauthorized", MediaType.TEXT_PLAIN));
                    emitter.complete();
                    return;
                }

                String answer = ragQaService.buildPersonalizedAnswer(principal.getName(), req.getVideoId(), req.getQuestion());
                for (int i = 0; i < answer.length(); i++) {
                    String ch = answer.substring(i, i + 1);
                    emitter.send(SseEmitter.event()
                            .name("chunk")
                            .data(ch, MediaType.TEXT_PLAIN));
                    try {
                        Thread.sleep(12L);
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                    }
                }
                emitter.send(SseEmitter.event().name("done").data("[DONE]", MediaType.TEXT_PLAIN));
                emitter.complete();
            } catch (Exception ex) {
                try {
                    emitter.send(SseEmitter.event().name("error").data(ex.getMessage(), MediaType.TEXT_PLAIN));
                } catch (Exception ignored) {
                }
                emitter.completeWithError(ex);
            }
        });

        return emitter;
    }
}
