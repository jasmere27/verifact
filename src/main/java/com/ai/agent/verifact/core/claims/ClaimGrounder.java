package com.ai.agent.verifact.core.claims;

import com.ai.agent.verifact.evidence.EvidenceRetriever;
import com.ai.agent.verifact.evidence.Grounding;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static com.ai.agent.verifact.evidence.Grounding.words;

/**
 * Keeps only claims that are really in the submitted text. A model may propose claims; it can't invent them,
 * put words in someone's mouth, or name a speaker the text doesn't.
 */
public final class ClaimGrounder {

    static final int MIN_QUOTE_WORDS = 4;
    static final int MIN_QUOTED_WORDS = 3;
    static final int MAX_CLAIM_CHARS = 400;
    static final int MAX_SPEAKER_CHARS = 120;
    static final int MAX_QUERIES = 2;

    private ClaimGrounder() {
    }

    /**
     * @param input the submitted text as {@link Grounding#material} normalises it
     */
    public static List<GroundedClaim> ground(List<ClaimCandidate> candidates, String input, ClaimProfile profile) {
        List<GroundedClaim> out = new ArrayList<>();
        if (candidates == null) {
            return out;
        }
        for (ClaimCandidate c : candidates) {
            if (c == null || c.quote() == null) {
                continue;
            }
            String q = words(c.quote());
            if (q.split(" ").length < MIN_QUOTE_WORDS || !input.contains(" " + q + " ")) {
                continue;
            }
            String type = type(c.type(), profile);
            String quotedWords = clean(c.quotedWords(), MAX_CLAIM_CHARS);
            if (type.equals(profile.quoteType()) && (words(quotedWords).split(" ").length < MIN_QUOTED_WORDS
                    || !input.contains(" " + words(quotedWords) + " "))) {
                type = profile.unverifiedQuoteType(); // no verifiable quoted words in the input
                quotedWords = "";
            }
            String speaker = clean(c.speaker(), MAX_SPEAKER_CHARS);
            if (!speaker.isEmpty() && !input.contains(" " + words(speaker) + " ")) {
                speaker = "";
            }
            String claim = clean(c.claim(), MAX_CLAIM_CHARS);
            if (claim.isBlank() || !Grounding.supported(claim, input)) {
                claim = clean(c.quote(), MAX_CLAIM_CHARS);
            }
            List<String> queries = new ArrayList<>();
            if (c.searchQueries() != null) {
                c.searchQueries().stream().map(EvidenceRetriever::cleanQuery).filter(x -> !x.isBlank()).limit(MAX_QUERIES)
                        .forEach(queries::add);
            }
            if (queries.isEmpty()) {
                queries.add(EvidenceRetriever.cleanQuery(claim));
            }
            out.add(new GroundedClaim(type, clean(c.quote(), MAX_CLAIM_CHARS), claim, speaker, quotedWords, List.copyOf(queries)));
            if (out.size() == profile.maxClaims()) {
                break;
            }
        }
        return out;
    }

    /** The model's type in the product's vocabulary ("date-time" → DATE_TIME), else the default. */
    static String type(String s, ClaimProfile profile) {
        String t = s == null ? "" : s.trim().toUpperCase(Locale.ROOT).replaceAll("[\\s-]+", "_");
        return profile.types().contains(t) ? t : profile.defaultType();
    }

    /** Whitespace collapsed and trimmed, at most {@code max} characters; "" for null. */
    public static String clean(String text, int max) {
        if (text == null) {
            return "";
        }
        String s = text.replaceAll("\\s+", " ").trim();
        return s.length() > max ? s.substring(0, max) : s;
    }
}
