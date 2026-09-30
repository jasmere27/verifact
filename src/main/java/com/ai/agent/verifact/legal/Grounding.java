package com.ai.agent.verifact.legal;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * "Did the model make this up?" checks for model-written text. Numbers (digits or number words:
 * section numbers, figures, deadlines) and case names ("Smith v. Jones") must appear in the material
 * the text is about: the user's description for intake fields, the cited source for source notes.
 * Anything else is the model's memory or an injected instruction, which LegalFact must not show.
 */
final class Grounding {

    private Grounding() {
    }

    private static final Pattern DIGITS = Pattern.compile("\\d+");
    private static final Pattern CASE_NAME = Pattern.compile("\\b[A-Z][\\w.'&-]*(?: [A-Z][\\w.'&-]*)* v(?:s)?\\. [A-Z][\\w.'&-]*");
    /** "one" is left out: it is everywhere in ordinary narrative ("one of my coworkers"). */
    private static final Set<String> NUMBER_WORDS = Set.of(
            "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten", "eleven", "twelve", "thirteen",
            "fourteen", "fifteen", "sixteen", "seventeen", "eighteen", "nineteen", "twenty", "thirty", "forty",
            "fifty", "sixty", "seventy", "eighty", "ninety", "hundred", "thousand", "million");
    private static final Pattern SENTENCE_END = Pattern.compile("(?<=[.!?])\\s+");

    /**
     * @param material the text it must be grounded in, already passed through {@link #words}
     *                 and padded with spaces
     */
    static boolean supported(String text, String material) {
        if (text == null || text.isBlank()) {
            return true;
        }
        String w = " " + words(text) + " ";
        Matcher digits = DIGITS.matcher(w);
        while (digits.find()) {
            if (!material.contains(" " + digits.group() + " ")) {
                return false;
            }
        }
        for (String token : w.trim().split(" ")) {
            if (NUMBER_WORDS.contains(token) && !material.contains(" " + token + " ")) {
                return false;
            }
        }
        Matcher caseName = CASE_NAME.matcher(text);
        while (caseName.find()) {
            if (!material.contains(" " + words(caseName.group()) + " ")) {
                return false;
            }
        }
        return true;
    }

    /** The text without sentences that fail {@link #supported}. */
    static String supportedSentences(String text, String material) {
        if (text == null || text.isBlank()) {
            return "";
        }
        List<String> kept = new ArrayList<>();
        for (String sentence : SENTENCE_END.split(text.trim())) {
            if (supported(sentence, material)) {
                kept.add(sentence);
            }
        }
        return String.join(" ", kept).trim();
    }

    /** Lower case, letters and digits only, ordinals as plain numbers ("3rd" → "3"), single spaces. */
    static String words(String text) {
        return text.toLowerCase(Locale.ROOT)
                .replaceAll("(\\d+)(?:st|nd|rd|th)\\b", "$1")
                .replaceAll("[^\\p{L}\\p{N}]+", " ")
                .trim();
    }

    /** {@link #words} padded with spaces, for {@code contains(" phrase ")} checks. */
    static String material(String... texts) {
        return " " + words(String.join(" ", texts)) + " ";
    }
}
