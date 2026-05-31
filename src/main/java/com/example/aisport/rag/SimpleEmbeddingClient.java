package com.example.aisport.rag;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

@Component
public class SimpleEmbeddingClient implements EmbeddingClient {

    private static final Logger log = LoggerFactory.getLogger(SimpleEmbeddingClient.class);
    private static final int DIM = 256;
    private static final int NGRAM_MIN = 2;
    private static final int NGRAM_MAX = 4;

    @Override
    public List<Float> embed(String text) {
        if (text == null || text.isBlank()) {
            return zeroVector();
        }

        double[] vec = new double[DIM];
        String normalized = text.toLowerCase().trim();

        for (int n = NGRAM_MIN; n <= NGRAM_MAX; n++) {
            for (int i = 0; i <= normalized.length() - n; i++) {
                String ngram = normalized.substring(i, i + n);
                int hash = (ngram.hashCode() & 0x7fffffff) % DIM;
                vec[hash] += 1.0;
            }
        }

        double norm = 0.0;
        for (double v : vec) norm += v * v;
        norm = Math.sqrt(norm);
        if (norm > 1e-10) {
            for (int i = 0; i < DIM; i++) vec[i] /= norm;
        }

        List<Float> result = new ArrayList<>(DIM);
        for (double v : vec) result.add((float) v);
        return result;
    }

    @Override
    public List<List<Float>> embedBatch(List<String> texts) {
        log.debug("Embedding batch of {} texts", texts.size());
        List<List<Float>> results = new ArrayList<>(texts.size());
        for (String text : texts) {
            results.add(embed(text));
        }
        return results;
    }

    private List<Float> zeroVector() {
        List<Float> z = new ArrayList<>(DIM);
        for (int i = 0; i < DIM; i++) z.add(0.0f);
        return z;
    }
}
