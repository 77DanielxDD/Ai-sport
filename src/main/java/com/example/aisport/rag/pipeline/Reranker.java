package com.example.aisport.rag.pipeline;

import com.example.aisport.rag.RetrievalQuery;
import com.example.aisport.rag.RetrievalResult;

import java.util.List;

public interface Reranker {

    List<RetrievalResult> rerank(RetrievalQuery query, List<RetrievalResult> results);
}
