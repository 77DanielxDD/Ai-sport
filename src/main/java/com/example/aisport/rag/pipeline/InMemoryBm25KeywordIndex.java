package com.example.aisport.rag.pipeline;

import com.example.aisport.rag.VectorDocument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class InMemoryBm25KeywordIndex implements KeywordIndex {

    private static final Logger log = LoggerFactory.getLogger(InMemoryBm25KeywordIndex.class);
    private static final double K1 = 1.5;
    private static final double B = 0.75;

    private final Map<String, VectorDocument> docs = new ConcurrentHashMap<>();
    private final Map<String, Map<String, Integer>> termFreqs = new ConcurrentHashMap<>();
    private final Map<String, Integer> docFreq = new ConcurrentHashMap<>();
    private int totalDocCount;
    private double avgDocLength;

    @Override
    public synchronized void index(VectorDocument doc) {
        if (doc.getId() == null || doc.getContent() == null) return;

        String prev = docs.containsKey(doc.getId()) ? docs.get(doc.getId()).getContent() : null;
        docs.put(doc.getId(), doc);

        List<String> tokens = tokenize(doc.getContent());
        if (prev != null) {
            List<String> prevTokens = tokenize(prev);
            removeTokens(doc.getId(), prevTokens);
        }
        addTokens(doc.getId(), tokens);
        recalcAvgLen();
        log.debug("Keyword index: doc={}, tokens={}", doc.getId(), tokens.size());
    }

    @Override
    public void indexBatch(List<VectorDocument> docs) {
        for (VectorDocument doc : docs) index(doc);
        log.info("Keyword index batch: {} docs, vocab={}", docs.size(), docFreq.size());
    }

    @Override
    public synchronized void delete(String docId) {
        VectorDocument doc = docs.remove(docId);
        if (doc != null) {
            List<String> tokens = tokenize(doc.getContent());
            removeTokens(docId, tokens);
            recalcAvgLen();
        }
    }

    @Override
    public List<ScoredKeywordHit> search(String query, int topK) {
        List<String> queryTokens = tokenize(query);
        if (queryTokens.isEmpty() || docs.isEmpty()) return List.of();

        int N = docs.size();
        if (totalDocCount > 0) N = totalDocCount;

        List<ScoredKeywordHit> hits = new ArrayList<>();
        for (Map.Entry<String, VectorDocument> entry : docs.entrySet()) {
            String docId = entry.getKey();
            Map<String, Integer> tf = termFreqs.get(docId);
            if (tf == null || tf.isEmpty()) continue;

            double score = 0.0;
            int dl = tf.values().stream().mapToInt(Integer::intValue).sum();
            double lengthNorm = avgDocLength > 0 ? (1 - B + B * dl / avgDocLength) : 1.0;

            for (String qt : queryTokens) {
                int f = tf.getOrDefault(qt, 0);
                if (f == 0) continue;
                int df = docFreq.getOrDefault(qt, 1);
                double idf = Math.log(1 + (N - df + 0.5) / (df + 0.5));
                double tfNorm = (K1 + 1) * f / (K1 * lengthNorm + f);
                score += idf * tfNorm;
            }
            if (score > 0) {
                hits.add(new ScoredKeywordHit(docId, entry.getValue().getTitle(), score));
            }
        }

        hits.sort((a, b) -> Double.compare(b.score(), a.score()));
        if (hits.size() > topK) hits = hits.subList(0, topK);
        return hits;
    }

    @Override
    public int size() { return docs.size(); }

    private void addTokens(String docId, List<String> tokens) {
        Map<String, Integer> tf = termFreqs.computeIfAbsent(docId, k -> new HashMap<>());
        for (String t : tokens) {
            tf.merge(t, 1, Integer::sum);
        }
        for (String t : new HashSet<>(tokens)) {
            docFreq.merge(t, 1, Integer::sum);
        }
        totalDocCount++;
    }

    private void removeTokens(String docId, List<String> tokens) {
        termFreqs.remove(docId);
        for (String t : new HashSet<>(tokens)) {
            Integer v = docFreq.get(t);
            if (v != null) {
                if (v <= 1) docFreq.remove(t);
                else docFreq.put(t, v - 1);
            }
        }
        totalDocCount = Math.max(0, totalDocCount - 1);
    }

    private void recalcAvgLen() {
        if (docs.isEmpty()) {
            avgDocLength = 0;
            return;
        }
        long total = 0;
        for (Map<String, Integer> tf : termFreqs.values()) {
            total += tf.values().stream().mapToInt(Integer::intValue).sum();
        }
        avgDocLength = (double) total / Math.max(1, termFreqs.size());
    }

    private List<String> tokenize(String text) {
        if (text == null || text.isBlank()) return List.of();
        String lower = text.toLowerCase(Locale.ROOT).trim();
        // Split on non-alphanumeric and non-CJK
        String[] parts = lower.split("[^a-z0-9\\u4e00-\\u9fa5]+");
        List<String> tokens = new ArrayList<>();
        for (String part : parts) {
            if (part.length() < 2) continue;
            // For CJK characters, split into bigrams
            if (part.codePoints().anyMatch(cp -> Character.UnicodeScript.of(cp) == Character.UnicodeScript.HAN)) {
                int[] cps = part.codePoints().toArray();
                for (int i = 0; i < cps.length; i++) {
                    tokens.add(new String(cps, i, 1));
                }
                // Add bigrams for CJK
                for (int i = 0; i < cps.length - 1; i++) {
                    tokens.add(new String(cps, i, 2));
                }
            } else {
                tokens.add(part);
            }
        }
        return tokens;
    }
}
