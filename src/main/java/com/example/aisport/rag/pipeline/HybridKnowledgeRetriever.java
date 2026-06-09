package com.example.aisport.rag.pipeline;

import com.example.aisport.rag.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;

@Component
public class HybridKnowledgeRetriever implements HybridRetriever {

    private static final Logger log = LoggerFactory.getLogger(HybridKnowledgeRetriever.class);
    private static final double VECTOR_WEIGHT = 0.6;
    private static final double KEYWORD_WEIGHT = 0.4;

    private final EmbeddingClient embeddingClient;
    private final VectorStore vectorStore;
    private final KeywordIndex keywordIndex;

    public HybridKnowledgeRetriever(EmbeddingClient embeddingClient,
                                     VectorStore vectorStore,
                                     KeywordIndex keywordIndex) {
        this.embeddingClient = embeddingClient;
        this.vectorStore = vectorStore;
        this.keywordIndex = keywordIndex;
    }

    @Override
    public List<RetrievalResult> retrieve(RetrievalQuery query) {
        return retrieveInternal(query, query.getTopK());
    }

    @Override
    public List<RetrievalResult> retrieveExpanded(RetrievalQuery query) {
        return retrieveInternal(query, Math.max(query.getTopK() + 5, 10));
    }

    private List<RetrievalResult> retrieveInternal(RetrievalQuery query, int topK) {
        String q = query.effectiveQuery();
        String typeFilter = query.getTypeFilter();
        log.debug("Hybrid retrieve: query='{}', topK={}, typeFilter={}", q, topK, typeFilter);

        // Dense retrieval
        List<ScoredVectorDocument> denseResults = denseRetrieve(q, typeFilter, topK);

        // Sparse retrieval
        List<KeywordIndex.ScoredKeywordHit> sparseResults = keywordIndex.search(q, topK);

        // Merge
        return mergeResults(denseResults, sparseResults, topK);
    }

    private List<ScoredVectorDocument> denseRetrieve(String query, String typeFilter, int topK) {
        try {
            List<Float> queryVec = embeddingClient.embed(query);
            VectorDocument queryDoc = new VectorDocument();
            queryDoc.setVector(queryVec);

            List<VectorDocument> docs = typeFilter != null
                    ? vectorStore.searchByType(queryDoc, typeFilter, topK)
                    : vectorStore.search(queryDoc, topK);

            // Need scores, so we search and recompute cosine for scoring
            List<ScoredVectorDocument> scored = new ArrayList<>();
            for (VectorDocument doc : docs) {
                double sim = doc.getVector() != null && queryVec != null
                        ? cosineSimilarity(queryVec, doc.getVector()) : 0.0;
                scored.add(new ScoredVectorDocument(doc, sim, "vector"));
            }
            return scored;
        } catch (Exception e) {
            log.warn("Dense retrieval failed: {}", e.getMessage());
            return List.of();
        }
    }

    private List<RetrievalResult> mergeResults(List<ScoredVectorDocument> dense,
                                                List<KeywordIndex.ScoredKeywordHit> sparse,
                                                int topK) {
        Map<String, RetrievalResult> merged = new LinkedHashMap<>();

        // Normalize dense scores to [0,1]
        double maxDense = dense.stream().mapToDouble(ScoredVectorDocument::getVectorScore).max().orElse(1.0);

        // Normalize sparse scores
        double maxSparse = sparse.stream().mapToDouble(KeywordIndex.ScoredKeywordHit::score).max().orElse(1.0);

        // Add dense results
        for (ScoredVectorDocument sd : dense) {
            double normScore = maxDense > 0 ? sd.getVectorScore() / maxDense : 0.0;
            RetrievalResult r = new RetrievalResult();
            r.setDocument(sd.getDocument());
            r.setVectorScore(sd.getVectorScore());
            r.setKeywordScore(0.0);
            r.setFinalScore(normScore * VECTOR_WEIGHT);
            r.setMatchedBy("vector");
            merged.put(sd.getDocument().getId(), r);
        }

        // Add sparse results
        for (KeywordIndex.ScoredKeywordHit hit : sparse) {
            double normScore = maxSparse > 0 ? hit.score() / maxSparse : 0.0;
            if (merged.containsKey(hit.docId())) {
                RetrievalResult existing = merged.get(hit.docId());
                existing.setKeywordScore(hit.score());
                existing.setFinalScore(existing.getFinalScore() + normScore * KEYWORD_WEIGHT);
                existing.setMatchedBy("both");
            } else {
                // Look up full document from vector store
                VectorDocument vecDoc = findDocument(hit.docId());
                if (vecDoc == null) continue;

                RetrievalResult r = new RetrievalResult();
                r.setDocument(vecDoc);
                r.setVectorScore(0.0);
                r.setKeywordScore(hit.score());
                r.setFinalScore(normScore * KEYWORD_WEIGHT);
                r.setMatchedBy("keyword");
                merged.put(hit.docId(), r);
            }
        }

        List<RetrievalResult> results = new ArrayList<>(merged.values());
        results.sort((a, b) -> Double.compare(b.getFinalScore(), a.getFinalScore()));

        if (results.size() > topK) {
            results = results.subList(0, topK);
        }

        return results;
    }

    private VectorDocument findDocument(String docId) {
        try {
            List<VectorDocument> all = vectorStore.allDocuments();
            for (VectorDocument d : all) {
                if (docId.equals(d.getId())) return d;
            }
        } catch (Exception ignored) {}
        return null;
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
}
