package com.ai.agent.verifact.research;

import com.ai.agent.verifact.common.ApiException;
import com.ai.agent.verifact.evidence.Grounding;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * Turns a source a student wants to save into a verified record (ADR-15): the details come from a fresh index
 * lookup by DOI or OpenAlex id, never from the client, and the "why it's relevant" note is kept only if its
 * quote is verbatim in that source's abstract. Shared by workspaces and capstone projects.
 */
@Component
public class SourceVerifier {

    private final ScholarlyIndex index;

    public SourceVerifier(ScholarlyIndex index) {
        this.index = index;
    }

    /**
     * @param topic   the workspace or project topic (the relevance note must be grounded in it or the quote)
     * @param country ISO code for local/foreign, or null
     * @throws ApiException 404 if the index doesn't have it, 503 if the index is down
     */
    public Discovery.FoundSource verify(String key, String relevance, String relevanceQuote, Discovery.Stance stance,
                                        String topic, String country) {
        DiscoveredWork work;
        try {
            work = index.work(key.trim()).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                    "That source couldn't be found in OpenAlex, so it can't be saved as verified."));
        } catch (ScholarlyIndex.ScholarlyIndexUnavailableException e) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "The research database is unavailable; try saving again shortly.", e);
        }
        String abs = work.work().abstractText();
        String quote = abs == null ? null : ResearchCheckService.verbatim(relevanceQuote, abs, topic);
        String why = quote == null ? null : cap(relevance, 300);
        if (why != null && !Grounding.supported(why, Grounding.material(topic, quote, work.work().title()))) {
            why = null;
            quote = null;
        }
        return ResearchDiscoveryService.toSource(work, why, quote, quote == null ? null : stance, country);
    }

    private static String cap(String s, int max) {
        if (s == null || s.isBlank()) {
            return null;
        }
        String t = s.strip();
        return t.length() > max ? t.substring(0, max) : t;
    }
}
