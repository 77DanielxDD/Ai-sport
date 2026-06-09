package com.example.aisport.rag.pipeline;

import com.example.aisport.rag.VectorDocument;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Component
public class FitnessKnowledgeChunker implements KnowledgeChunker {

    private static final int MIN_CHUNK_LENGTH = 20;

    @Override
    public List<VectorDocument> chunk(String rawText, String sourceName) {
        if (rawText == null || rawText.isBlank()) return List.of();

        String[] blocks = rawText.split("\\r?\\n\\r?\\n");
        List<VectorDocument> docs = new ArrayList<>();
        int total = 0;
        // Count valid blocks first
        for (String block : blocks) {
            if (block.trim().length() >= MIN_CHUNK_LENGTH) total++;
        }

        int idx = 0;
        for (String block : blocks) {
            String text = block.trim();
            if (text.length() < MIN_CHUNK_LENGTH) continue;
            docs.add(chunkToDoc(text, sourceName, idx, total));
            idx++;
        }
        return docs;
    }

    @Override
    public VectorDocument chunkToDoc(String chunkContent, String sourceName, int index, int total) {
        String firstLine = chunkContent.lines().findFirst().orElse("");
        String title = firstLine.length() > 40 ? firstLine.substring(0, 40) + "..." : firstLine;

        VectorDocument doc = new VectorDocument(
                "knowledge_chunk_" + index,
                "knowledge",
                title,
                chunkContent,
                sourceName
        );
        doc.setCreatedAt(LocalDateTime.now());
        doc.setUpdatedAt(doc.getCreatedAt());
        return doc;
    }
}
