package com.example.aisport.rag;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class VectorSearchService {

    private static final Logger log = LoggerFactory.getLogger(VectorSearchService.class);

    private final EmbeddingClient embeddingClient;
    private final VectorStore vectorStore;

    public VectorSearchService(EmbeddingClient embeddingClient, VectorStore vectorStore) {
        this.embeddingClient = embeddingClient;
        this.vectorStore = vectorStore;
    }

    public List<VectorDocument> search(String query, int topK) {
        log.debug("Vector search: query='{}', topK={}", query, topK);
        List<Float> queryVec = embeddingClient.embed(query);
        VectorDocument queryDoc = new VectorDocument();
        queryDoc.setVector(queryVec);
        return vectorStore.searchByType(queryDoc, "knowledge", topK);
    }

    public List<VectorDocument> searchByType(String query, String type, int topK) {
        List<Float> queryVec = embeddingClient.embed(query);
        VectorDocument queryDoc = new VectorDocument();
        queryDoc.setVector(queryVec);
        return vectorStore.searchByType(queryDoc, type, topK);
    }
}
