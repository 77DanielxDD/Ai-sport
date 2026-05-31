package com.example.aisport.rag;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Component
public class InMemoryVectorStore implements VectorStore {

    private static final Logger log = LoggerFactory.getLogger(InMemoryVectorStore.class);
    private final Map<String, VectorDocument> store = new ConcurrentHashMap<>();

    @Override
    public void index(VectorDocument doc) {
        if (doc.getId() == null) {
            throw new IllegalArgumentException("Document id required");
        }
        store.put(doc.getId(), doc);
        log.debug("Indexed document: {} (type={})", doc.getId(), doc.getType());
    }

    @Override
    public void indexBatch(List<VectorDocument> docs) {
        for (VectorDocument doc : docs) {
            index(doc);
        }
        log.info("Indexed {} documents, total={}", docs.size(), store.size());
    }

    @Override
    public void delete(String id) {
        store.remove(id);
    }

    @Override
    public List<VectorDocument> search(VectorDocument query, int topK) {
        return searchInternal(query, null, topK);
    }

    @Override
    public List<VectorDocument> searchByType(VectorDocument query, String type, int topK) {
        return searchInternal(query, type, topK);
    }

    @Override
    public List<VectorDocument> allDocuments() {
        return List.copyOf(store.values());
    }

    private List<VectorDocument> searchInternal(VectorDocument query, String typeFilter, int topK) {
        if (store.isEmpty()) return List.of();
        List<Float> queryVec = query.getVector();
        if (queryVec == null || queryVec.isEmpty()) return List.of();

        List<ScoredDoc> scored = new ArrayList<>();

        for (VectorDocument doc : store.values()) {
            if (typeFilter != null && !typeFilter.equals(doc.getType())) continue;
            if (doc.getVector() == null || doc.getVector().isEmpty()) continue;

            double sim = cosineSimilarity(queryVec, doc.getVector());
            scored.add(new ScoredDoc(doc, sim));
        }

        scored.sort(Comparator.comparingDouble(ScoredDoc::score).reversed());

        return scored.stream()
                .limit(Math.max(1, topK))
                .map(sd -> sd.doc)
                .collect(Collectors.toList());
    }

    private double cosineSimilarity(List<Float> a, List<Float> b) {
        int len = Math.min(a.size(), b.size());
        double dot = 0.0, normA = 0.0, normB = 0.0;
        for (int i = 0; i < len; i++) {
            float va = a.get(i);
            float vb = b.get(i);
            dot += va * vb;
            normA += va * va;
            normB += vb * vb;
        }
        double denom = Math.sqrt(normA) * Math.sqrt(normB);
        return denom < 1e-10 ? 0.0 : dot / denom;
    }

    private record ScoredDoc(VectorDocument doc, double score) {}
}
