package com.example.aisport.rag.pipeline;

import com.example.aisport.rag.RetrievalQuery;

public interface QuestionRouter {

    String route(RetrievalQuery query);

    enum Route {
        TRAINING_PLAN("training_plan"),
        FORM_CORRECTION("form_correction"),
        TREND_REVIEW("trend_review"),
        GENERAL_KNOWLEDGE("general_knowledge");

        private final String value;
        Route(String value) { this.value = value; }
        public String value() { return value; }
    }
}
