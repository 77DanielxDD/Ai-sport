package com.example.aisport.controller;

import com.example.aisport.rag.KnowledgeIngestionService;
import com.example.aisport.rag.VectorDocument;
import com.example.aisport.service.UserService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/admin/knowledge")
public class KnowledgeAdminController {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeAdminController.class);

    private final KnowledgeIngestionService ingestionService;
    private final UserService userService;

    public KnowledgeAdminController(KnowledgeIngestionService ingestionService,
                                     UserService userService) {
        this.ingestionService = ingestionService;
        this.userService = userService;
    }

    @PostMapping("/reindex")
    public ResponseEntity<?> reindex(Principal principal) {
        if (principal == null || !userService.isAdmin(principal.getName())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", "Admin required"));
        }

        long start = System.currentTimeMillis();
        try {
            KnowledgeIngestionService.ReindexResult result = ingestionService.reindex();
            List<VectorDocument> docs = ingestionService.listAll();
            long elapsed = System.currentTimeMillis() - start;

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("status", "ok");
            body.put("chunkCount", docs.size());
            body.put("added", result.getAdded());
            body.put("updated", result.getUpdated());
            body.put("deleted", result.getDeleted());
            body.put("skipped", result.getSkipped());
            body.put("sourceHash", result.getSourceHash());
            body.put("durationMs", elapsed);
            body.put("indexedAt", LocalDateTime.now().toString());

            log.info("Knowledge reindex complete: {} chunks, added={}, updated={}, deleted={}, skipped={} in {}ms",
                    docs.size(), result.getAdded(), result.getUpdated(), result.getDeleted(), result.getSkipped(), elapsed);
            return ResponseEntity.ok(body);
        } catch (Exception e) {
            log.error("Knowledge reindex failed", e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "status", "error",
                    "error", e.getMessage()
            ));
        }
    }
}
