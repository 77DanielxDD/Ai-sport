package com.example.aisport.agent;

import com.example.aisport.rag.SimpleEmbeddingClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SimpleEmbeddingClientTest {

    private SimpleEmbeddingClient client;

    @BeforeEach
    void setUp() {
        client = new SimpleEmbeddingClient();
    }

    @Test
    void shouldProduceFixedSizeVector() {
        List<Float> vec = client.embed("深蹲动作分析");
        assertNotNull(vec);
        assertEquals(256, vec.size());
    }

    @Test
    void shouldProduceUnitVector() {
        List<Float> vec = client.embed("俯卧撑肘部外翻纠正");
        double norm = Math.sqrt(vec.stream().mapToDouble(v -> v * v).sum());
        assertEquals(1.0, norm, 0.01);
    }

    @Test
    void shouldReturnZeroVectorForEmptyText() {
        List<Float> vec = client.embed("");
        assertNotNull(vec);
        assertEquals(256, vec.size());
        assertTrue(vec.stream().allMatch(v -> v == 0.0f));
    }

    @Test
    void shouldReturnZeroVectorForNullText() {
        List<Float> vec = client.embed(null);
        assertNotNull(vec);
        assertTrue(vec.stream().allMatch(v -> v == 0.0f));
    }

    @Test
    void similarTextsShouldHaveHighCosineSimilarity() {
        List<Float> a = client.embed("深蹲深度不足");
        List<Float> b = client.embed("深蹲幅度不够");
        double sim = cosineSimilarity(a, b);

        List<Float> c = client.embed("引体向上训练计划");
        double diffSim = cosineSimilarity(a, c);

        assertTrue(sim > diffSim, "Similar texts should have higher cosine similarity than different texts");
    }

    @Test
    void sameTextShouldHaveNearPerfectSimilarity() {
        List<Float> a = client.embed("如何提高俯卧撑次数");
        List<Float> b = client.embed("如何提高俯卧撑次数");
        double sim = cosineSimilarity(a, b);
        assertEquals(1.0, sim, 0.001);
    }

    @Test
    void batchEmbedShouldReturnSameCount() {
        List<String> texts = List.of("深蹲", "俯卧撑", "硬拉");
        List<List<Float>> vectors = client.embedBatch(texts);
        assertEquals(3, vectors.size());
        assertEquals(256, vectors.get(0).size());
        assertEquals(256, vectors.get(1).size());
        assertEquals(256, vectors.get(2).size());
    }

    @Test
    void chineseAndEnglishShouldBothWork() {
        List<Float> a = client.embed("squat depth");
        List<Float> b = client.embed("深蹲深度");
        assertNotNull(a);
        assertNotNull(b);
        assertEquals(256, a.size());
        assertEquals(256, b.size());
    }

    private double cosineSimilarity(List<Float> a, List<Float> b) {
        double dot = 0.0, nA = 0.0, nB = 0.0;
        for (int i = 0; i < a.size(); i++) {
            dot += a.get(i) * b.get(i);
            nA += a.get(i) * a.get(i);
            nB += b.get(i) * b.get(i);
        }
        double denom = Math.sqrt(nA) * Math.sqrt(nB);
        return denom < 1e-10 ? 0.0 : dot / denom;
    }
}
