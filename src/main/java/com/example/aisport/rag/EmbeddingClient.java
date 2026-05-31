package com.example.aisport.rag;

import java.util.List;

public interface EmbeddingClient {

    List<Float> embed(String text);

    List<List<Float>> embedBatch(List<String> texts);
}
