package com.example.aisport.rag.pipeline;

import com.example.aisport.rag.RetrievalQuery;
import com.example.aisport.rag.RetrievalResult;
import com.example.aisport.rag.RetrievedContext;
import org.springframework.stereotype.Component;

import java.util.*;

@Component
public class CitationContextAssembler implements ContextAssembler {

    @Override
    public List<RetrievedContext> assemble(RetrievalQuery query, List<RetrievalResult> results) {
        if (results == null || results.isEmpty()) return List.of();

        List<RetrievedContext> contexts = new ArrayList<>();
        Set<String> seenIds = new LinkedHashSet<>();

        for (int i = 0; i < results.size(); i++) {
            RetrievalResult result = results.get(i);
            String id = result.chunkId();
            if (id == null || seenIds.contains(id)) continue;
            seenIds.add(id);

            RetrievedContext ctx = RetrievedContext.fromResult(result);
            ctx.getMetadata().put("rank", i + 1);
            ctx.getMetadata().put("originalScore", result.getFinalScore());
            contexts.add(ctx);
        }

        return contexts;
    }
}
