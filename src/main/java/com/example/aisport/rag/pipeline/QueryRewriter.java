package com.example.aisport.rag.pipeline;

import com.example.aisport.rag.RetrievalQuery;

import java.util.List;
import java.util.Map;

public interface QueryRewriter {

    RetrievalQuery rewrite(String question, Map<String, Object> userProfile);

    RetrievalQuery rewriteWithContext(String question, Map<String, Object> userProfile,
                                      String exerciseType, List<String> videoTips,
                                      List<String> recentQuestions);
}
