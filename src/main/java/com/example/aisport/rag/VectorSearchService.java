package com.example.aisport.rag;

import com.example.aisport.rag.pipeline.HybridRetriever;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class VectorSearchService {

    private static final Logger log = LoggerFactory.getLogger(VectorSearchService.class);

    private final EmbeddingClient embeddingClient;
    private final VectorStore vectorStore;
    private final HybridRetriever hybridRetriever;

    public VectorSearchService(EmbeddingClient embeddingClient,
                                VectorStore vectorStore,
                                @Autowired(required = false) HybridRetriever hybridRetriever) {
        this.embeddingClient = embeddingClient;
        this.vectorStore = vectorStore;
        this.hybridRetriever = hybridRetriever;
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

    public List<ScoredVectorDocument> searchScored(String query, String type, int topK) {
        List<Float> queryVec = embeddingClient.embed(query);
        VectorDocument queryDoc = new VectorDocument();
        queryDoc.setVector(queryVec);

        List<VectorDocument> docs = type != null
                ? vectorStore.searchByType(queryDoc, type, topK)
                : vectorStore.search(queryDoc, topK);

        List<ScoredVectorDocument> scored = new ArrayList<>();
        for (VectorDocument doc : docs) {
            double sim = cosineSimilarity(queryVec, doc.getVector());
            scored.add(new ScoredVectorDocument(doc, sim, "vector"));
        }
        return scored;
    }

    public List<RetrievalResult> hybridSearch(RetrievalQuery query) {
        if (hybridRetriever == null) {
            log.debug("Hybrid retriever not available, falling back to dense-only");
            return denseFallback(query);
        }
        log.debug("Hybrid search: query='{}', topK={}", query.effectiveQuery(), query.getTopK());
        return hybridRetriever.retrieve(query);
    }

    public List<RetrievalResult> hybridSearchExpanded(RetrievalQuery query) {
        if (hybridRetriever == null) {
            return denseFallback(query);
        }
        log.debug("Expanded hybrid search: query='{}'", query.effectiveQuery());
        return hybridRetriever.retrieveExpanded(query);
    }

    private List<RetrievalResult> denseFallback(RetrievalQuery query) {
        List<ScoredVectorDocument> scored = searchScored(query.effectiveQuery(), query.getTypeFilter(), query.getTopK());
        List<RetrievalResult> results = new ArrayList<>();
        for (ScoredVectorDocument s : scored) {
            RetrievalResult r = new RetrievalResult();
            r.setDocument(s.getDocument());
            r.setVectorScore(s.getVectorScore());
            r.setFinalScore(s.getFinalScore());
            r.setMatchedBy("vector");
            results.add(r);
        }
        return results;
    }

    private double cosineSimilarity(List<Float> a, List<Float> b) {
        if (a == null || b == null) return 0.0;
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
}
