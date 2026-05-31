package com.example.aisport.rag;

import java.util.List;

public interface VectorStore {

    void index(VectorDocument doc);

    void indexBatch(List<VectorDocument> docs);

    void delete(String id);

    List<VectorDocument> search(VectorDocument query, int topK);

    List<VectorDocument> searchByType(VectorDocument query, String type, int topK);

    List<VectorDocument> allDocuments();
}
