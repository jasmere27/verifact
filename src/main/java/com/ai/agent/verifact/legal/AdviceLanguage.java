package com.ai.agent.verifact.legal;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Backstop for the "information, not advice" rule (legalfact.md): sentences that tell the reader
 * they have a case, will win, should sue, are entitled to something, or that something is illegal
 * are removed from model-written text. The prompt forbids them; this makes it deterministic.
 */
final class AdviceLanguage {

    private AdviceLanguage() {
    }

    private static final Pattern ADVICE = Pattern.compile("(?i)"
            + "\\b(?:you|they|he|she|the person|the client|the user|the employee|the tenant)(?:'ve)? (?:clearly |likely |probably |definitely )?"
            + "(?:have|has) (?:got )?(?:a |an )?(?:very )?(?:strong |good |valid |solid |winning |viable )?(?:legal )?(?:case|claim|lawsuit)\\b"
            + "(?!\\s+(?:number|no\\.|id|file|denied|approved|pending|was|is|with (?:the )?(?:insurer|insurance)))"
            + "|\\b(?:you|they|he|she)(?:'ll| will| would| are likely to| could| should) (?:likely |probably |definitely )?win\\b"
            + "|\\byou (?:should|must|need to|ought to) (?:sue|file|take legal action|bring (?:a )?(?:claim|lawsuit))"
            + "|\\b(?:you are|you're) entitled to\\b"
            + "|\\b(?:is|was|are|were) (?:clearly |definitely |plainly |obviously )?(?:illegal|unlawful)\\b"
            + "|\\b(?:violated|broke|breached) the law\\b"
            + "|\\bguarantee(?:d|s)?\\b"
            + "|\\bthis (?:is|constitutes) (?:wrongful termination|retaliation|discrimination|harassment|fraud)\\b");

    private static final Pattern SENTENCE_END = Pattern.compile("(?<=[.!?])\\s+");

    static boolean isAdvice(String text) {
        return text != null && ADVICE.matcher(text).find();
    }

    /** The text without its advice-like sentences (possibly empty). */
    static String strip(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        List<String> kept = new ArrayList<>();
        for (String sentence : SENTENCE_END.split(text.trim())) {
            if (!isAdvice(sentence)) {
                kept.add(sentence);
            }
        }
        return String.join(" ", kept).trim();
    }
}
