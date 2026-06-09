package com.example.aisport.rag.pipeline;

import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

@Component
public class FitnessKnowledgeCleaner implements KnowledgeCleaner {

    private static final Pattern MULTI_NEWLINE = Pattern.compile("\\n{3,}");
    private static final Pattern MULTI_SPACE = Pattern.compile("[ \\t]{2,}");
    private static final Pattern LEADING_TRAILING_DASHES = Pattern.compile("^[-–—]+\\s*|\\s*[-–—]+$", Pattern.MULTILINE);

    @Override
    public String clean(String rawText) {
        if (rawText == null || rawText.isBlank()) return "";

        String text = rawText;
        // Normalize excessive newlines
        text = MULTI_NEWLINE.matcher(text).replaceAll("\n\n");
        // Normalize excessive spaces
        text = MULTI_SPACE.matcher(text).replaceAll(" ");
        // Strip leading/trailing dashes per line
        text = LEADING_TRAILING_DASHES.matcher(text).replaceAll("");
        // Trim
        text = text.trim();

        return text;
    }
}
