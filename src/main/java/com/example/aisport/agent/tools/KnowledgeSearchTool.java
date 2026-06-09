package com.example.aisport.agent.tools;

import com.example.aisport.agent.AgentContext;
import com.example.aisport.agent.AgentTool;
import com.example.aisport.rag.*;
import com.example.aisport.rag.pipeline.HybridRetriever;
import com.example.aisport.rag.pipeline.QueryRewriter;
import org.springframework.stereotype.Component;

import java.util.*;

@Component
public class KnowledgeSearchTool implements AgentTool {

    private final VectorSearchService vectorSearchService;
    private final HybridRetriever hybridRetriever;
    private final QueryRewriter queryRewriter;

    public KnowledgeSearchTool(VectorSearchService vectorSearchService,
                                HybridRetriever hybridRetriever,
                                QueryRewriter queryRewriter) {
        this.vectorSearchService = vectorSearchService;
        this.hybridRetriever = hybridRetriever;
        this.queryRewriter = queryRewriter;
    }

    @Override
    public String name() { return "search_knowledge"; }

    @Override
    public String description() {
        return "Search the fitness knowledge base using hybrid (vector + keyword) search. "
             + "Covers exercise form, corrective strategies, training principles, and common mistakes. "
             + "Use this to get evidence-based training advice for specific exercises or issues.";
    }

    @Override
    public Map<String, Object> schema() {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("type", "object");
        s.put("description", "Search knowledge base. query is required.");
        Map<String, Object> props = new LinkedHashMap<>();
        Map<String, Object> queryProp = new LinkedHashMap<>();
        queryProp.put("type", "string");
        queryProp.put("description", "Search query about exercise form, technique, or training advice");
        props.put("query", queryProp);
        Map<String, Object> topKProp = new LinkedHashMap<>();
        topKProp.put("type", "integer");
        topKProp.put("description", "Number of results to return (max 5)");
        topKProp.put("default", 3);
        props.put("topK", topKProp);
        s.put("properties", props);
        s.put("required", List.of("query"));
        return s;
    }

    @Override
    public Map<String, Object> execute(AgentContext context, Map<String, Object> args) {
        String query = args != null ? String.valueOf(args.getOrDefault("query", "")) : "";
        if (query.isBlank() || "null".equals(query)) {
            return Map.of("error", "query is required");
        }

        int topK = args.get("topK") instanceof Number n ? Math.min(n.intValue(), 5) : 3;

        try {
            // Build RetrievalQuery from context + args
            RetrievalQuery rq = queryRewriter.rewrite(query, Map.of());
            rq.setTopK(topK);
            rq.setTypeFilter("knowledge");

            List<RetrievalResult> results = vectorSearchService.hybridSearch(rq);

            List<Map<String, Object>> docs = new ArrayList<>();
            for (RetrievalResult r : results) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("id", r.chunkId());
                item.put("title", r.title());
                item.put("content", r.getDocument() != null ? r.getDocument().getContent() : "");
                item.put("source", r.source());
                item.put("score", Math.round(r.getFinalScore() * 1000.0) / 1000.0);
                item.put("matchedBy", r.getMatchedBy());
                item.put("chunkId", r.chunkId());
                docs.add(item);
            }

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("query", query);
            result.put("totalResults", docs.size());
            result.put("results", docs);
            return result;
        } catch (Exception e) {
            return Map.of("error", "Search failed: " + e.getMessage(), "query", query, "results", List.of());
        }
    }
}
