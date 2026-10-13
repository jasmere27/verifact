package com.ai.agent.verifact.intelligence;

import com.ai.agent.verifact.common.ApiException;
import org.springframework.http.HttpStatus;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Input hygiene for user-entered text: printable characters, collapsed whitespace, explicit limits. */
public final class Text {

    private Text() {
    }

    /** Printable text, whitespace collapsed and capped; null or blank → null. */
    public static String clean(String raw, int max) {
        if (raw == null) {
            return null;
        }
        String s = raw.replaceAll("[\\p{Cc}\\p{Cf}]", " ").replaceAll("\\s+", " ").trim();
        if (s.isEmpty()) {
            return null;
        }
        return s.length() > max ? s.substring(0, max).trim() : s;
    }

    /** Like {@link #clean} but keeps line breaks (for drafts and notes). */
    public static String cleanMultiline(String raw, int max) {
        if (raw == null) {
            return null;
        }
        String s = raw.replace("\r\n", "\n").replaceAll("[\\p{Cc}&&[^\\n\\t]]|\\p{Cf}", " ").replaceAll("[ \\t]+", " ")
                .replaceAll("\\n{3,}", "\n\n").trim();
        if (s.isEmpty()) {
            return null;
        }
        return s.length() > max ? s.substring(0, max).trim() : s;
    }

    /** Required text: 400 with {@code message} when missing, or when longer than {@code max}. */
    public static String required(String raw, int max, String message) {
        String s = clean(raw, Integer.MAX_VALUE);
        if (s == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, message);
        }
        if (s.length() > max) {
            throw new ApiException(HttpStatus.BAD_REQUEST, message.replaceAll("\\.$", "") + " (at most " + max + " characters).");
        }
        return s;
    }

    /** A de-duplicated (case-insensitive) list of short terms; 400 when there are more than {@code maxItems}. */
    public static List<String> terms(List<String> raw, int maxItems, int maxChars, String what) {
        if (raw == null) {
            return List.of();
        }
        Set<String> seen = new LinkedHashSet<>();
        List<String> out = new ArrayList<>();
        for (String r : raw) {
            String s = clean(r, maxChars);
            if (s != null && seen.add(s.toLowerCase(Locale.ROOT))) {
                out.add(s);
            }
        }
        if (out.size() > maxItems) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Too many " + what + ": at most " + maxItems + ".");
        }
        return out;
    }
}
