package com.example.aisport.rag.pipeline;

import com.example.aisport.rag.RetrievalResult;

import java.util.List;

public interface RetrievalConfidenceEvaluator {

    boolean lowConfidence(List<RetrievalResult> results);

    double confidence(List<RetrievalResult> results);
}
