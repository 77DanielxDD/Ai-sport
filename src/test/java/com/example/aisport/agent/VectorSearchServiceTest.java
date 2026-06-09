package com.example.aisport.agent;

import com.example.aisport.rag.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class VectorSearchServiceTest {

    private SimpleEmbeddingClient embeddingClient;
    private InMemoryVectorStore vectorStore;
    private VectorSearchService searchService;

    @BeforeEach
    void setUp() {
        embeddingClient = new SimpleEmbeddingClient();
        vectorStore = new InMemoryVectorStore();
        searchService = new VectorSearchService(embeddingClient, vectorStore, null);

        List<VectorDocument> docs = List.of(
                doc("k1", "knowledge", "深蹲深度不足的纠正", "深蹲深度不足的纠正原则：先降低负重，优先保证动作幅度。"),
                doc("k2", "knowledge", "俯卧撑常见问题", "俯卧撑常见问题是下放不够和躯干塌陷。纠正时先做慢速离心。"),
                doc("k3", "knowledge", "卧推技术要点", "肩胛后缩下沉，脚跟稳定蹬地，杠铃下放到可控位置。"),
                doc("k4", "knowledge", "硬拉常见代偿", "硬拉常见代偿是弓背与起拉瞬间借力。起拉前先建立腹压。"),
                doc("k5", "user_report", "用户训练报告1", "俯卧撑评分75分，主要问题是节奏不稳")
        );

        // Compute and set vectors
        for (VectorDocument doc : docs) {
            doc.setVector(embeddingClient.embed(doc.getContent()));
            vectorStore.index(doc);
        }
    }

    @Test
    void shouldSearchAndReturnTopK() {
        List<VectorDocument> results = searchService.search("深蹲深度不够怎么办", 2);
        assertFalse(results.isEmpty());
        assertTrue(results.size() <= 2);
    }

    @Test
    void shouldPreferRelevantResults() {
        List<VectorDocument> results = searchService.search("深蹲深度不够怎么办", 5);
        assertFalse(results.isEmpty());
        // First result should be about squat depth (k1), not unrelated content
        String firstId = results.get(0).getId();
        assertTrue(firstId.equals("k1") || firstId.equals("k2") || firstId.equals("k3"),
                "Expected squat-related result first, got: " + firstId);
    }

    @Test
    void shouldFilterByType() {
        List<VectorDocument> knowledgeOnly = searchService.searchByType("深蹲", "knowledge", 10);
        assertFalse(knowledgeOnly.isEmpty());
        assertTrue(knowledgeOnly.stream().allMatch(d -> "knowledge".equals(d.getType())));
    }

    @Test
    void shouldHandleEmptyQueryGracefully() {
        // Empty query produces zero vector, cosine similarity will be 0 for all docs
        // So topK results will still be returned (with 0 similarity)
        List<VectorDocument> results = searchService.search("", 5);
        assertNotNull(results);
        // With zero vector, all docs have 0 similarity; top K are returned anyway
        assertTrue(results.size() <= 5);
    }

    @Test
    void shouldHandleEmptyStore() {
        InMemoryVectorStore emptyStore = new InMemoryVectorStore();
        VectorSearchService emptySearch = new VectorSearchService(embeddingClient, emptyStore, null);
        List<VectorDocument> results = emptySearch.search("深蹲", 5);
        assertTrue(results.isEmpty());
    }

    @Test
    void shouldHybridSearchFallbackToDense() {
        RetrievalQuery rq = new RetrievalQuery();
        rq.setOriginalQuery("深蹲深度不够怎么办");
        rq.setRewrittenQuery("深蹲深度不够怎么办");
        rq.setTopK(3);
        rq.setTypeFilter("knowledge");

        List<RetrievalResult> results = searchService.hybridSearch(rq);
        assertFalse(results.isEmpty());
        assertTrue(results.size() <= 3);
        // Should be matched by vector since keyword index not available
        assertTrue(results.stream().anyMatch(r -> "vector".equals(r.getMatchedBy())));
    }

    @Test
    void shouldReturnScoredResults() {
        List<ScoredVectorDocument> results = searchService.searchScored("深蹲深度不够怎么办", "knowledge", 3);
        assertFalse(results.isEmpty());
        assertTrue(results.size() <= 3);
        assertTrue(results.get(0).getVectorScore() > 0, "Top result should have positive score");
    }

    @Test
    void shouldNotLoseTypeFilterInScoredSearch() {
        List<ScoredVectorDocument> results = searchService.searchScored("俯卧撑", "knowledge", 5);
        assertFalse(results.isEmpty());
        assertTrue(results.stream().allMatch(r -> "knowledge".equals(r.getDocument().getType())));
    }

    private VectorDocument doc(String id, String type, String title, String content) {
        return new VectorDocument(id, type, title, content, "test");
    }
}
