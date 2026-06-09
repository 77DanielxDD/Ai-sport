package com.example.aisport.rag.pipeline;

import com.example.aisport.rag.VectorDocument;

import java.util.List;

public interface KeywordIndex {

    void index(VectorDocument doc);

    void indexBatch(List<VectorDocument> docs);

    void delete(String docId);

    List<ScoredKeywordHit> search(String query, int topK);

    int size();

    record ScoredKeywordHit(String docId, String docTitle, double score) {}
}
