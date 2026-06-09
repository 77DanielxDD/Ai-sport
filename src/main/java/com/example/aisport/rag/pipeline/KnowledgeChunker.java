package com.example.aisport.rag.pipeline;

import com.example.aisport.rag.VectorDocument;

import java.util.List;

public interface KnowledgeChunker {

    List<VectorDocument> chunk(String rawText, String sourceName);

    VectorDocument chunkToDoc(String chunkContent, String sourceName, int index, int total);
}
