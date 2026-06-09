package com.example.aisport.rag.pipeline;

import com.example.aisport.rag.RetrievalQuery;
import com.example.aisport.rag.RetrievalResult;
import com.example.aisport.rag.RetrievedContext;

import java.util.List;

public interface ContextAssembler {

    List<RetrievedContext> assemble(RetrievalQuery query, List<RetrievalResult> results);
}
