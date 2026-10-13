package com.ai.agent.verifact.intelligence.monitoring;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Decides in code (no model) whether a retrieved item is about the project and how relevant it is. Terms match
 * as whole words, case- and accent-insensitively.
 * <ul>
 *   <li>No entity term (brand, alias, product, executive) in the title or snippet → not relevant (dropped).</li>
 *   <li>Any excluded term → dropped.</li>
 *   <li>HIGH: an entity term plus a keyword or topic of concern.</li>
 *   <li>MEDIUM: an entity term in the title.</li>
 *   <li>LOW: an entity term only in the snippet.</li>
 * </ul>
 */
public final class Relevance {

    public enum Level { HIGH, MEDIUM, LOW }

    /** @param matched the terms found, for the analyst ("why is this here?") */
    public record Result(Level level, List<String> matched) {}

    private Relevance() {
    }

    /** @return null when the item isn't about the project or is excluded */
    public static Result evaluate(String brand, MonitoringConfig config, String title, String snippet) {
        String t = normalize(title);
        String body = normalize(snippet);
        String all = t + " " + body;
        for (String excluded : config.excludedTerms()) {
            if (contains(all, excluded)) {
                return null;
            }
        }
        List<String> matched = new ArrayList<>();
        boolean inTitle = false;
        for (String term : config.entityTerms(brand)) {
            if (contains(all, term)) {
                matched.add(term);
                inTitle |= contains(t, term);
            }
        }
        if (matched.isEmpty()) {
            return null;
        }
        boolean concern = false;
        List<String> concerns = new ArrayList<>(config.keywords());
        concerns.addAll(config.topics());
        for (String term : concerns) {
            if (contains(all, term)) {
                matched.add(term);
                concern = true;
            }
        }
        Level level = concern ? Level.HIGH : inTitle ? Level.MEDIUM : Level.LOW;
        return new Result(level, List.copyOf(matched));
    }

    static boolean contains(String normalizedText, String term) {
        String n = normalize(term);
        if (n.isBlank()) {
            return false;
        }
        return Pattern.compile("(?<![\\p{L}\\p{N}])" + Pattern.quote(n) + "(?![\\p{L}\\p{N}])").matcher(normalizedText).find();
    }

    static String normalize(String s) {
        if (s == null) {
            return "";
        }
        String d = Normalizer.normalize(s, Normalizer.Form.NFKD).replaceAll("\\p{M}", "");
        return d.toLowerCase(Locale.ROOT).replaceAll("[\\u2018\\u2019]", "'").replaceAll("\\s+", " ").trim();
    }
}
