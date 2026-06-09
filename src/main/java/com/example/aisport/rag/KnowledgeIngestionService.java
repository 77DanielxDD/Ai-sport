package com.example.aisport.rag;

import com.example.aisport.rag.pipeline.KeywordIndex;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.util.StreamUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class KnowledgeIngestionService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeIngestionService.class);

    private final EmbeddingClient embeddingClient;
    private final VectorStore vectorStore;
    private final KeywordIndex keywordIndex;

    private String currentSourceHash;

    public KnowledgeIngestionService(EmbeddingClient embeddingClient,
                                      VectorStore vectorStore,
                                      KeywordIndex keywordIndex) {
        this.embeddingClient = embeddingClient;
        this.vectorStore = vectorStore;
        this.keywordIndex = keywordIndex;
    }

    @PostConstruct
    public void init() {
        String raw = loadRawText();
        if (raw == null || raw.isBlank()) {
            log.warn("No knowledge documents loaded from fitness_knowledge_zh.txt");
            return;
        }
        List<VectorDocument> docs = chunkRaw(raw, "fitness_knowledge_zh.txt");
        if (docs.isEmpty()) {
            log.warn("No chunks created");
            return;
        }
        embedAndIndex(docs);
        currentSourceHash = sourceHash(raw);
        log.info("Knowledge ingestion complete: {} chunks indexed, sourceHash={}", docs.size(), currentSourceHash);
    }

    public ReindexResult reindex() {
        String raw = loadRawText();
        if (raw == null || raw.isBlank()) {
            log.warn("No knowledge source to reindex");
            return new ReindexResult(0, 0, 0, 0, currentSourceHash);
        }

        String newSourceHash = sourceHash(raw);

        if (newSourceHash.equals(currentSourceHash)) {
            List<VectorDocument> existing = listAll();
            log.info("Source unchanged (hash={}), skipping {} chunks", currentSourceHash, existing.size());
            return new ReindexResult(0, 0, 0, existing.size(), currentSourceHash);
        }

        // Chunk new content
        List<VectorDocument> newDocs = chunkRaw(raw, "fitness_knowledge_zh.txt");
        Map<String, VectorDocument> existingMap = listAll().stream()
                .collect(Collectors.toMap(VectorDocument::getId, d -> d, (a, b) -> a));

        List<VectorDocument> toAdd = new ArrayList<>();
        List<VectorDocument> toUpdate = new ArrayList<>();
        int deleted = 0;
        int skipped = 0;

        // Find existing IDs that are not in new docs -> delete
        Set<String> newIds = newDocs.stream().map(VectorDocument::getId).collect(Collectors.toSet());
        for (String existingId : existingMap.keySet()) {
            if (!newIds.contains(existingId)) {
                vectorStore.delete(existingId);
                keywordIndex.delete(existingId);
                deleted++;
            }
        }

        for (VectorDocument newDoc : newDocs) {
            VectorDocument existing = existingMap.get(newDoc.getId());
            if (existing == null) {
                toAdd.add(newDoc);
            } else {
                String newChunkHash = chunkHash(newDoc.getContent());
                String existingChunkHash = existing.getChunkHash();
                if (!newChunkHash.equals(existingChunkHash)) {
                    newDoc.setCreatedAt(existing.getCreatedAt());
                    newDoc.setVector(existing.getVector()); // Will be recomputed
                    toUpdate.add(newDoc);
                } else {
                    skipped++;
                }
            }
        }

        // Embed new and changed chunks
        if (!toAdd.isEmpty()) {
            embedAndIndex(toAdd);
        }
        if (!toUpdate.isEmpty()) {
            embedAndIndex(toUpdate);
        }

        currentSourceHash = newSourceHash;
        log.info("Reindex done: added={}, updated={}, deleted={}, skipped={}, sourceHash={}",
                toAdd.size(), toUpdate.size(), deleted, skipped, currentSourceHash);
        return new ReindexResult(toAdd.size(), toUpdate.size(), deleted, skipped, currentSourceHash);
    }

    public List<VectorDocument> listAll() {
        return vectorStore.allDocuments().stream()
                .filter(d -> "knowledge".equals(d.getType()))
                .toList();
    }

    public String getCurrentSourceHash() { return currentSourceHash; }

    private void embedAndIndex(List<VectorDocument> docs) {
        List<List<Float>> vectors = embeddingClient.embedBatch(
                docs.stream().map(VectorDocument::getContent).toList()
        );
        for (int i = 0; i < docs.size(); i++) {
            docs.get(i).setVector(vectors.get(i));
        }
        vectorStore.indexBatch(docs);
        keywordIndex.indexBatch(docs);
    }

    private List<VectorDocument> chunkRaw(String raw, String sourceName) {
        String[] blocks = raw.split("\\r?\\n\\r?\\n");
        List<VectorDocument> docs = new ArrayList<>();
        int total = 0;
        for (String block : blocks) {
            if (block.trim().length() >= 20) total++;
        }

        int idx = 0;
        for (String block : blocks) {
            String text = block.trim();
            if (text.length() < 20) continue;

            String firstLine = text.lines().findFirst().orElse("");
            String title = firstLine.length() > 40 ? firstLine.substring(0, 40) + "..." : firstLine;

            VectorDocument doc = new VectorDocument(
                    "knowledge_chunk_" + idx,
                    "knowledge",
                    title,
                    text,
                    sourceName
            );
            doc.setChunkHash(chunkHash(text));
            doc.setSourceHash(sourceHash(raw));
            LocalDateTime now = LocalDateTime.now();
            doc.setCreatedAt(now);
            doc.setUpdatedAt(now);
            docs.add(doc);
            idx++;
        }
        return docs;
    }

    private String loadRawText() {
        try {
            ClassPathResource resource = new ClassPathResource("rag/fitness_knowledge_zh.txt");
            if (!resource.exists()) return null;
            return StreamUtils.copyToString(resource.getInputStream(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.error("Failed to load knowledge base", e);
            return null;
        }
    }

    private String chunkHash(String content) {
        return sha256(content != null ? content : "");
    }

    private String sourceHash(String raw) {
        return sha256(raw != null ? raw : "");
    }

    private String sha256(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(input.hashCode());
        }
    }

    public static class ReindexResult {
        private final int added;
        private final int updated;
        private final int deleted;
        private final int skipped;
        private final String sourceHash;

        public ReindexResult(int added, int updated, int deleted, int skipped, String sourceHash) {
            this.added = added;
            this.updated = updated;
            this.deleted = deleted;
            this.skipped = skipped;
            this.sourceHash = sourceHash;
        }

        public int getAdded() { return added; }
        public int getUpdated() { return updated; }
        public int getDeleted() { return deleted; }
        public int getSkipped() { return skipped; }
        public String getSourceHash() { return sourceHash; }
    }
}
