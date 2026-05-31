package com.example.aisport.rag;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.util.StreamUtils;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

@Service
public class KnowledgeIngestionService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeIngestionService.class);

    private final EmbeddingClient embeddingClient;
    private final VectorStore vectorStore;

    public KnowledgeIngestionService(EmbeddingClient embeddingClient, VectorStore vectorStore) {
        this.embeddingClient = embeddingClient;
        this.vectorStore = vectorStore;
    }

    @PostConstruct
    public void init() {
        List<VectorDocument> docs = loadKnowledgeDocs();
        if (docs.isEmpty()) {
            log.warn("No knowledge documents loaded from fitness_knowledge_zh.txt");
            return;
        }
        List<List<Float>> vectors = embeddingClient.embedBatch(
                docs.stream().map(VectorDocument::getContent).toList()
        );
        for (int i = 0; i < docs.size(); i++) {
            docs.get(i).setVector(vectors.get(i));
        }
        vectorStore.indexBatch(docs);
        log.info("Knowledge ingestion complete: {} chunks indexed", docs.size());
    }

    public synchronized void reindex() {
        // Remove existing knowledge docs
        List<VectorDocument> existing = listAll();
        for (VectorDocument doc : existing) {
            vectorStore.delete(doc.getId());
        }
        // Re-load and index
        init();
        log.info("Knowledge reindex complete: replaced {} old chunks", existing.size());
    }

    public List<VectorDocument> listAll() {
        return vectorStore.allDocuments().stream()
                .filter(d -> "knowledge".equals(d.getType()))
                .toList();
    }

    private List<VectorDocument> loadKnowledgeDocs() {
        try {
            ClassPathResource resource = new ClassPathResource("rag/fitness_knowledge_zh.txt");
            if (!resource.exists()) return List.of();

            String content = StreamUtils.copyToString(resource.getInputStream(), StandardCharsets.UTF_8);
            String[] blocks = content.split("\\r?\\n\\r?\\n");

            List<VectorDocument> docs = new ArrayList<>();
            int idx = 0;
            for (String block : blocks) {
                String text = block.trim();
                if (text.isBlank()) continue;

                String firstLine = text.lines().findFirst().orElse("");
                String title = firstLine.length() > 40 ? firstLine.substring(0, 40) + "..." : firstLine;

                VectorDocument doc = new VectorDocument(
                        "knowledge_chunk_" + (idx++),
                        "knowledge",
                        title,
                        text,
                        "fitness_knowledge_zh.txt"
                );
                docs.add(doc);
            }
            return docs;
        } catch (Exception e) {
            log.error("Failed to load knowledge base", e);
            return List.of();
        }
    }
}
