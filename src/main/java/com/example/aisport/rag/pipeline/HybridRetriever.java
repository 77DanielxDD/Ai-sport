package com.example.aisport.rag.pipeline;

import com.example.aisport.rag.RetrievalQuery;
import com.example.aisport.rag.RetrievalResult;

import java.util.List;

public interface HybridRetriever {

    List<RetrievalResult> retrieve(RetrievalQuery query);

    List<RetrievalResult> retrieveExpanded(RetrievalQuery query);
}
