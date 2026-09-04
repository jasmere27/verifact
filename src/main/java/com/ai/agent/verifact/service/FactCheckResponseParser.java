package com.ai.agent.verifact.service;

import org.springframework.stereotype.Component;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracts the structured fields the AiService prompt requires (see the
 * "OUTPUT STRUCTURE" section of the prompt template) out of the LLM's
 * free-form markdown response, for persistence. Best-effort: any field the
 * model omits or reformats is left null rather than failing the request.
 */
@Component
public class FactCheckResponseParser {

    private static final Pattern CLASSIFICATION =
            Pattern.compile("\\*\\*Classification:\\*\\*\\s*([A-Za-z]+)", Pattern.CASE_INSENSITIVE);

    private static final Pattern CONFIDENCE =
            Pattern.compile("\\*\\*Confidence Score:\\*\\*\\s*(\\d{1,3})\\s*%");

    private static final Pattern SOURCES_SECTION =
            Pattern.compile("Sources[^\\n]*\\n(.*?)(?=\\n\\s*(Cybersecurity Tip|Original Input)|\\z)",
                    Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private static final Pattern TIPS_SECTION =
            Pattern.compile("Cybersecurity Tip[^\\n]*:?\\s*(.*?)(?=\\n\\s*Original Input|\\z)",
                    Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    public String extractClassification(String response) {
        return firstGroup(CLASSIFICATION, response);
    }

    public Integer extractConfidenceScore(String response) {
        String value = firstGroup(CONFIDENCE, response);
        if (value == null) {
            return null;
        }
        try {
            return Integer.valueOf(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public String extractSources(String response) {
        String value = firstGroup(SOURCES_SECTION, response);
        return value == null ? null : value.trim();
    }

    public String extractCybersecurityTips(String response) {
        String value = firstGroup(TIPS_SECTION, response);
        return value == null ? null : value.trim();
    }

    private String firstGroup(Pattern pattern, String input) {
        if (input == null) {
            return null;
        }
        Matcher matcher = pattern.matcher(input);
        return matcher.find() ? matcher.group(1).trim() : null;
    }
}
