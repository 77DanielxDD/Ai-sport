package com.example.aisport.controller;

import com.example.aisport.rag.KnowledgeIngestionService;
import com.example.aisport.rag.VectorDocument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/admin/knowledge")
public class KnowledgeAdminController {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeAdminController.class);

    private final KnowledgeIngestionService ingestionService;

    public KnowledgeAdminController(KnowledgeIngestionService ingestionService) {
        this.ingestionService = ingestionService;
    }

    @PostMapping("/reindex")
    public ResponseEntity<?> reindex() {
        long start = System.currentTimeMillis();
        try {
            ingestionService.reindex();
            List<VectorDocument> docs = ingestionService.listAll();
            long elapsed = System.currentTimeMillis() - start;

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "ok");
            result.put("chunkCount", docs.size());
            result.put("durationMs", elapsed);
            result.put("indexedAt", LocalDateTime.now().toString());

            log.info("Knowledge reindex complete: {} chunks in {}ms", docs.size(), elapsed);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("Knowledge reindex failed", e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "status", "error",
                    "error", e.getMessage()
            ));
        }
    }
}
