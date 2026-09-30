package com.ai.agent.verifact.evidence;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * "Did the model make this up?" checks for model-written text, shared by every vertical. Numbers (digits or number words:
 * section numbers, figures, deadlines) and case names ("Smith v. Jones") must appear in the material
 * the text is about: the user's description for intake fields, the cited source for source notes.
 * Anything else is the model's memory or an injected instruction, which LegalFact must not show.
 */
public final class Grounding {

    private Grounding() {
    }

    private static final Pattern DIGITS = Pattern.compile("\\d+");
    private static final Pattern CASE_NAME = Pattern.compile("\\b[A-Z][\\w.'&-]*(?: [A-Z][\\w.'&-]*)* v(?:s)?\\. [A-Z][\\w.'&-]*");
    /** "one" is left out: it is everywhere in ordinary narrative ("one of my coworkers"). */
    private static final Set<String> NUMBER_WORDS = Set.of(
            "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten", "eleven", "twelve", "thirteen",
            "fourteen", "fifteen", "sixteen", "seventeen", "eighteen", "nineteen", "twenty", "thirty", "forty",
            "fifty", "sixty", "seventy", "eighty", "ninety", "hundred", "thousand", "million");
    /** "8" and "eight" are the same figure: sources and rewrites switch between them. */
    private static final Map<String, String> DIGIT_WORD = Map.ofEntries(
            Map.entry("2", "two"), Map.entry("3", "three"), Map.entry("4", "four"), Map.entry("5", "five"),
            Map.entry("6", "six"), Map.entry("7", "seven"), Map.entry("8", "eight"), Map.entry("9", "nine"),
            Map.entry("10", "ten"), Map.entry("11", "eleven"), Map.entry("12", "twelve"), Map.entry("13", "thirteen"),
            Map.entry("14", "fourteen"), Map.entry("15", "fifteen"), Map.entry("16", "sixteen"),
            Map.entry("17", "seventeen"), Map.entry("18", "eighteen"), Map.entry("19", "nineteen"),
            Map.entry("20", "twenty"), Map.entry("30", "thirty"), Map.entry("40", "forty"), Map.entry("50", "fifty"),
            Map.entry("60", "sixty"), Map.entry("70", "seventy"), Map.entry("80", "eighty"), Map.entry("90", "ninety"));
    private static final Pattern SENTENCE_END = Pattern.compile("(?<=[.!?])\\s+");

    /**
     * @param material the text it must be grounded in, already passed through {@link #words}
     *                 and padded with spaces
     */
    public static boolean supported(String text, String material) {
        if (text == null || text.isBlank()) {
            return true;
        }
        String w = " " + words(text) + " ";
        Matcher digits = DIGITS.matcher(w);
        while (digits.find()) {
            String d = digits.group();
            String word = DIGIT_WORD.get(d);
            if (!material.contains(" " + d + " ") && (word == null || !material.contains(" " + word + " "))) {
                return false;
            }
        }
        for (String token : w.trim().split(" ")) {
            if (NUMBER_WORDS.contains(token) && !material.contains(" " + token + " ")
                    && DIGIT_WORD.entrySet().stream().noneMatch(e -> e.getValue().equals(token)
                            && material.contains(" " + e.getKey() + " "))) {
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
    public static String supportedSentences(String text, String material) {
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

    private static final Pattern TOKEN = Pattern.compile("[\\p{L}\\p{N}]+");

    /**
     * Where {@code quote} appears in {@code text} word for word (ignoring case, accents and punctuation),
     * as the text's own characters, so the displayed words are the source's, not a paraphrase.
     *
     * @return the span, or null if fewer than {@code minWords} words or not found in sequence
     */
    public static String findSpan(String quote, String text, int minWords) {
        if (quote == null || text == null) {
            return null;
        }
        String q = words(quote);
        if (q.isEmpty() || q.split(" ").length < minWords) {
            return null;
        }
        List<String> target = List.of(q.split(" "));
        List<String> tokens = new ArrayList<>();
        List<int[]> spans = new ArrayList<>();
        Matcher m = TOKEN.matcher(text);
        while (m.find()) {
            String w = words(m.group());
            if (!w.isEmpty()) {
                for (String part : w.split(" ")) { // "3rd" etc. normalise to one token; keep alignment simple
                    tokens.add(part);
                    spans.add(new int[]{m.start(), m.end()});
                }
            }
        }
        for (int i = 0; i + target.size() <= tokens.size(); i++) {
            if (tokens.subList(i, i + target.size()).equals(target)) {
                return text.substring(spans.get(i)[0], spans.get(i + target.size() - 1)[1]).replaceAll("\\s+", " ");
            }
        }
        return null;
    }

    /** Lower case, accents removed ("Müller" → "muller"), letters and digits only, ordinals as plain numbers ("3rd" → "3"), single spaces. */
    public static String words(String text) {
        return java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFKD).replaceAll("\\p{M}+", "")
                .toLowerCase(Locale.ROOT)
                .replaceAll("(\\d+)(?:st|nd|rd|th)\\b", "$1")
                .replaceAll("[^\\p{L}\\p{N}]+", " ")
                .trim();
    }

    /** {@link #words} padded with spaces, for {@code contains(" phrase ")} checks. */
    public static String material(String... texts) {
        return " " + words(String.join(" ", texts)) + " ";
    }
}
