package com.ai.agent.verifact.verification;

import java.util.Locale;

/**
 * What an uploaded image visibly shows, as read by the vision model: context for the reader, never
 * evidence. Only on reports for images read by a vision model (null for OCR, text, links, audio).
 *
 * @param kind        what sort of image it is
 * @param shownSource the account, person or outlet the image presents as its source, as written in it; null if none
 * @param shownDate   a date or time written in the image, as written; null if none
 * @param description one or two neutral sentences on what the image shows
 */
public record ImageContext(Kind kind, String shownSource, String shownDate, String description) {

    public enum Kind {
        SOCIAL_MEDIA_POST, NEWS_HEADLINE, ARTICLE, CHART, MEME, PHOTO, DOCUMENT, OTHER;

        /** Lenient: the model's label, or OTHER if it isn't one of ours. */
        static Kind parse(String value) {
            if (value == null) {
                return OTHER;
            }
            try {
                return valueOf(value.trim().toUpperCase(Locale.ROOT).replaceAll("[\\s-]+", "_"));
            } catch (IllegalArgumentException e) {
                return OTHER;
            }
        }
    }
}
