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

    // A section header line: e.g. "### Sources", "**Sources:**", "Sources (Clickably formatted)".
    // Anchored to the whole line so it never matches the word appearing mid-sentence
    // (e.g. "...verified through multiple credible sources.").
    private static final String HEADER_LINE = "(?im)^[ \\t]*#{0,3}[ \\t]*\\*{0,2}%s\\*{0,2}:?[^\\n]*$";

    private static final Pattern SOURCES_HEADER = Pattern.compile(String.format(HEADER_LINE, "Sources"));
    private static final Pattern TIPS_HEADER = Pattern.compile(String.format(HEADER_LINE, "Cybersecurity Tips?"));
    private static final Pattern ORIGINAL_INPUT_HEADER = Pattern.compile(String.format(HEADER_LINE, "Original Input"));

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
        return extractSection(response, SOURCES_HEADER, TIPS_HEADER, ORIGINAL_INPUT_HEADER);
    }

    public String extractCybersecurityTips(String response) {
        return extractSection(response, TIPS_HEADER, ORIGINAL_INPUT_HEADER);
    }

    /**
     * Returns the text between a header line matching {@code header} and whichever of
     * {@code stopHeaders} appears next (or the end of the response), trimmed. Null if
     * {@code header} isn't found.
     */
    private String extractSection(String response, Pattern header, Pattern... stopHeaders) {
        if (response == null) {
            return null;
        }
        Matcher headerMatcher = header.matcher(response);
        if (!headerMatcher.find()) {
            return null;
        }
        int start = headerMatcher.end();

        int end = response.length();
        for (Pattern stopHeader : stopHeaders) {
            Matcher stopMatcher = stopHeader.matcher(response);
            if (stopMatcher.find(start) && stopMatcher.start() < end) {
                end = stopMatcher.start();
            }
        }

        return response.substring(start, end).trim();
    }

    private String firstGroup(Pattern pattern, String input) {
        if (input == null) {
            return null;
        }
        Matcher matcher = pattern.matcher(input);
        return matcher.find() ? matcher.group(1).trim() : null;
    }
}
