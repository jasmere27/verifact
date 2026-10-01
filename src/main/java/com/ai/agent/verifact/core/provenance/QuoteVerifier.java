package com.ai.agent.verifact.core.provenance;

import com.ai.agent.verifact.evidence.Evidence;
import com.ai.agent.verifact.evidence.Grounding;

import java.util.List;

/**
 * Looks for quoted words, word for word, in the retrieved sources: done in code, independent of the model.
 * Not finding a quote means "not in these sources", never "fake".
 */
public final class QuoteVerifier {

    private QuoteVerifier() {
    }

    /** @return the first source (in retrieval order) containing at least {@code minWords} of the quote in a row, or null */
    public static SourceExcerpt locate(String quotedWords, List<Evidence> sources, int minWords, int maxChars) {
        for (Evidence e : sources) {
            String span = Grounding.findSpan(quotedWords, CitationValidator.citable(e), minWords);
            if (span != null) {
                return new SourceExcerpt(e.id(), CitationValidator.cap(span, maxChars));
            }
        }
        return null;
    }
}
