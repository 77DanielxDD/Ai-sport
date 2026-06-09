package com.example.aisport.rag;

import com.example.aisport.rag.pipeline.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class RagRetrievalPipelineTest {

    private SimpleEmbeddingClient embeddingClient;
    private InMemoryVectorStore vectorStore;
    private InMemoryBm25KeywordIndex keywordIndex;
    private HybridKnowledgeRetriever hybridRetriever;
    private HeuristicReranker reranker;
    private CitationContextAssembler contextAssembler;
    private DefaultRetrievalConfidenceEvaluator confidenceEvaluator;
    private RuleBasedQueryRewriter queryRewriter;
    private RuleBasedQuestionRouter questionRouter;

    @BeforeEach
    void setUp() {
        embeddingClient = new SimpleEmbeddingClient();
        vectorStore = new InMemoryVectorStore();
        keywordIndex = new InMemoryBm25KeywordIndex();
        hybridRetriever = new HybridKnowledgeRetriever(embeddingClient, vectorStore, keywordIndex);
        reranker = new HeuristicReranker();
        contextAssembler = new CitationContextAssembler();
        confidenceEvaluator = new DefaultRetrievalConfidenceEvaluator();
        queryRewriter = new RuleBasedQueryRewriter();
        questionRouter = new RuleBasedQuestionRouter();

        // Index test knowledge chunks
        List<VectorDocument> docs = List.of(
                doc("knowledge_chunk_0", "knowledge", "深蹲深度不足的纠正",
                        "深蹲深度不足的纠正原则：先降低负重，优先保证动作幅度。使用慢速离心控制，在底部保持1-2秒停顿。"),
                doc("knowledge_chunk_1", "knowledge", "俯卧撑常见问题与纠正",
                        "俯卧撑常见问题是下放不够和躯干塌陷。纠正时先做慢速离心，保持核心稳定，肩胛骨后缩。"),
                doc("knowledge_chunk_2", "knowledge", "卧推技术要点详解",
                        "肩胛后缩下沉，脚跟稳定蹬地，杠铃下放到可控位置。注意保持手腕中立位，避免过度外展。"),
                doc("knowledge_chunk_3", "knowledge", "硬拉常见代偿与解决方案",
                        "硬拉常见代偿是弓背与起拉瞬间借力。起拉前先建立腹压，激活背阔肌，保持杠铃贴近身体。"),
                doc("knowledge_chunk_4", "knowledge", "训练计划制定原则",
                        "训练计划应遵循渐进超负荷原则。每周增加不超过5%的训练量，确保充分恢复。周期化训练包括基础期、提升期和巅峰期。")
        );

        for (VectorDocument doc : docs) {
            doc.setVector(embeddingClient.embed(doc.getContent()));
            doc.setChunkHash("hash_" + doc.getId());
            doc.setSourceHash("source_hash_v1");
            vectorStore.index(doc);
            keywordIndex.index(doc);
        }
    }

    @Test
    void shouldTriggerLowConfidenceRetrieval() {
        // Construct results with explicitly low scores to test confidence threshold
        List<RetrievalResult> results = new ArrayList<>();

        RetrievalResult r1 = new RetrievalResult();
        r1.setDocument(new VectorDocument("id1", "knowledge", "t", "content", "src"));
        r1.setFinalScore(0.15); // Below 0.35 threshold
        r1.setMatchedBy("vector");
        results.add(r1);

        assertTrue(confidenceEvaluator.lowConfidence(results),
                "Low top-1 score (<0.35) should trigger low confidence");

        // Test with too few contexts
        assertTrue(confidenceEvaluator.lowConfidence(List.of()),
                "Empty results should trigger low confidence");
    }

    @Test
    void shouldNotTriggerLowConfidenceForRelevantQuery() {
        RetrievalQuery rq = new RetrievalQuery();
        rq.setOriginalQuery("深蹲深度不足怎么纠正");
        rq.setRewrittenQuery("深蹲深度不足怎么纠正");
        rq.setTopK(5);

        List<RetrievalResult> results = hybridRetriever.retrieve(rq);
        results = reranker.rerank(rq, results);
        assertFalse(results.isEmpty());

        double conf = confidenceEvaluator.confidence(results);
        assertTrue(conf > 0.0, "Confidence should be positive for relevant query: " + conf);
    }

    @Test
    void shouldExpandRetrievalOnLowConfidence() {
        RetrievalQuery rq = new RetrievalQuery();
        rq.setOriginalQuery("如何改善训练");
        rq.setRewrittenQuery("如何改善训练");
        rq.setTopK(3);

        List<RetrievalResult> results = hybridRetriever.retrieve(rq);
        results = reranker.rerank(rq, results);

        if (confidenceEvaluator.lowConfidence(results)) {
            // Second retrieval with expanded topK
            List<RetrievalResult> expanded = hybridRetriever.retrieveExpanded(rq);
            // Merge
            Map<String, RetrievalResult> merged = new LinkedHashMap<>();
            for (RetrievalResult r : results) {
                if (r.chunkId() != null) merged.put(r.chunkId(), r);
            }
            for (RetrievalResult r : expanded) {
                if (r.chunkId() != null) merged.putIfAbsent(r.chunkId(), r);
            }
            results = new ArrayList<>(merged.values());
            results = reranker.rerank(rq, results);
        }

        assertFalse(results.isEmpty());
        // After expansion, we should have at least as many results
        assertTrue(results.size() >= 1, "Expanded retrieval should return results");
    }

    @Test
    void shouldAssembleCitationsOnlyFromRetrievedResults() {
        RetrievalQuery rq = new RetrievalQuery();
        rq.setOriginalQuery("深蹲深度纠正");
        rq.setRewrittenQuery("深蹲深度纠正");
        rq.setTopK(3);

        List<RetrievalResult> results = hybridRetriever.retrieve(rq);
        results = reranker.rerank(rq, results);
        List<RetrievedContext> contexts = contextAssembler.assemble(rq, results);

        assertFalse(contexts.isEmpty());

        // Every context should have a valid chunkId from the indexed docs
        Set<String> validIds = Set.of("knowledge_chunk_0", "knowledge_chunk_1",
                "knowledge_chunk_2", "knowledge_chunk_3", "knowledge_chunk_4");
        for (RetrievedContext ctx : contexts) {
            assertNotNull(ctx.getChunkId(), "Every context should have a chunkId");
            assertTrue(validIds.contains(ctx.getChunkId()),
                    "Citation chunkId should be from indexed docs, got: " + ctx.getChunkId());
            assertNotNull(ctx.getSource(), "Every context should have a source");
            assertNotNull(ctx.getSnippet(), "Every context should have a snippet");
        }
    }

    @Test
    void shouldComputeHitAtK() {
        String query = "深蹲深度不足怎么纠正";
        String expectedChunkId = "knowledge_chunk_0";

        RetrievalQuery rq = new RetrievalQuery();
        rq.setOriginalQuery(query);
        rq.setRewrittenQuery(query);
        rq.setTopK(5);

        List<RetrievalResult> results = hybridRetriever.retrieve(rq);
        results = reranker.rerank(rq, results);

        // Hit@K: is expectedChunkId in top K?
        boolean hit = false;
        for (int i = 0; i < Math.min(results.size(), 5); i++) {
            if (expectedChunkId.equals(results.get(i).chunkId())) {
                hit = true;
                break;
            }
        }
        assertTrue(hit, "Expected chunk should be in top 5 results. Got: "
                + results.stream().map(RetrievalResult::chunkId).toList());
    }

    @Test
    void shouldComputeMRR() {
        String query = "深蹲深度纠正";
        String expectedChunkId = "knowledge_chunk_0";

        RetrievalQuery rq = new RetrievalQuery();
        rq.setOriginalQuery(query);
        rq.setRewrittenQuery(query);
        rq.setTopK(5);

        List<RetrievalResult> results = hybridRetriever.retrieve(rq);
        results = reranker.rerank(rq, results);

        // MRR = 1 / rank
        double mrr = 0.0;
        for (int i = 0; i < results.size(); i++) {
            if (expectedChunkId.equals(results.get(i).chunkId())) {
                mrr = 1.0 / (i + 1);
                break;
            }
        }
        assertTrue(mrr > 0.0, "MRR should be positive, expected chunk not found in results: "
                + results.stream().map(RetrievalResult::chunkId).toList());
    }

    @Test
    void shouldComputeCitationCoverage() {
        RetrievalQuery rq = new RetrievalQuery();
        rq.setOriginalQuery("深蹲深度纠正 训练计划");
        rq.setRewrittenQuery("深蹲深度纠正 训练计划");
        rq.setTopK(3);

        List<RetrievalResult> results = hybridRetriever.retrieve(rq);
        results = reranker.rerank(rq, results);
        List<RetrievedContext> contexts = contextAssembler.assemble(rq, results);

        // CitationCoverage = all citations must come from retrieval results
        Set<String> retrievedIds = new HashSet<>();
        for (RetrievalResult r : results) {
            if (r.chunkId() != null) retrievedIds.add(r.chunkId());
        }

        int totalCitations = contexts.size();
        int coveredCitations = 0;
        for (RetrievedContext ctx : contexts) {
            if (retrievedIds.contains(ctx.getChunkId())) {
                coveredCitations++;
            }
        }

        double coverage = totalCitations > 0 ? (double) coveredCitations / totalCitations : 0.0;
        assertEquals(1.0, coverage, 0.01, "All citations should be from retrieved results");
    }

    @Test
    void shouldRerankWithExerciseTypeBonus() {
        RetrievalQuery rq = new RetrievalQuery();
        rq.setOriginalQuery("如何改善动作");
        rq.setRewrittenQuery("如何改善动作");
        rq.setExerciseType("深蹲");
        rq.setTopK(4);

        List<RetrievalResult> results = hybridRetriever.retrieve(rq);
        results = reranker.rerank(rq, results);

        assertFalse(results.isEmpty());
        // Results related to 深蹲 should get a bonus and rise in ranking
        boolean hasSquatResult = results.stream().anyMatch(
                r -> r.getDocument() != null && r.getDocument().getTitle().contains("深蹲"));
        assertTrue(hasSquatResult, "Should include squat-related results after reranking");
    }

    @Test
    void shouldRouteQuestionToFormCorrection() {
        RetrievalQuery rq = new RetrievalQuery();
        rq.setOriginalQuery("我的深蹲姿势有什么问题，怎么纠正");
        rq.setRewrittenQuery("我的深蹲姿势有什么问题，怎么纠正");
        rq.setExerciseType("深蹲");
        rq.setVideoTips(List.of("深度不足", "膝盖内扣"));

        String route = questionRouter.route(rq);
        assertEquals("form_correction", route, "Form correction query should route to form_correction");
    }

    @Test
    void shouldRouteQuestionToTrainingPlan() {
        RetrievalQuery rq = new RetrievalQuery();
        rq.setOriginalQuery("帮我制定一周的训练计划");
        rq.setRewrittenQuery("帮我制定一周的训练计划");

        String route = questionRouter.route(rq);
        assertEquals("training_plan", route, "Plan query should route to training_plan");
    }

    @Test
    void shouldRouteQuestionToGeneralKnowledge() {
        RetrievalQuery rq = new RetrievalQuery();
        rq.setOriginalQuery("什么是渐进超负荷");
        rq.setRewrittenQuery("什么是渐进超负荷");

        String route = questionRouter.route(rq);
        assertEquals("general_knowledge", route, "General query should route to general_knowledge");
    }

    private VectorDocument doc(String id, String type, String title, String content) {
        VectorDocument d = new VectorDocument(id, type, title, content, "fitness_knowledge_zh.txt");
        d.setCreatedAt(java.time.LocalDateTime.now());
        d.setUpdatedAt(d.getCreatedAt());
        return d;
    }
}
