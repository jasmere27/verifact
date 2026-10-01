package com.ai.agent.verifact.core.provenance;

import com.ai.agent.verifact.evidence.Evidence;
import com.ai.agent.verifact.evidence.Grounding;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Accepts a citation only when it names a retrieved source and its words are that source's own: a model can't
 * cite a source it wasn't given or put words in a source's mouth.
 */
public final class CitationValidator {

    private CitationValidator() {
    }

    /** What a citation may quote from a source: the parts the retriever kept. */
    public static String citable(Evidence source) {
        return source.title() + " " + source.snippet();
    }

    /** Sources by id, in retrieval order. */
    public static Map<String, Evidence> byId(List<Evidence> sources) {
        Map<String, Evidence> byId = new LinkedHashMap<>();
        sources.forEach(e -> byId.put(e.id(), e));
        return byId;
    }

    /**
     * @param sourceId the cited source id as the model wrote it ("e3" matches "E3")
     * @param words    the words the model says the source uses
     * @param minWords shortest run of matching words accepted
     * @return the source's actual words (capped at {@code maxChars}), or null if the source is unknown or the words aren't there
     */
    public static SourceExcerpt verbatim(String sourceId, String words, Map<String, Evidence> byId, int minWords, int maxChars) {
        if (sourceId == null || words == null) {
            return null;
        }
        Evidence e = byId.get(sourceId.trim().toUpperCase(Locale.ROOT));
        if (e == null) {
            return null;
        }
        String span = Grounding.findSpan(words, citable(e), minWords);
        return span == null ? null : new SourceExcerpt(e.id(), cap(span, maxChars));
    }

    public static String cap(String s, int maxChars) {
        return s.length() > maxChars ? s.substring(0, maxChars) + "…" : s;
    }
}
