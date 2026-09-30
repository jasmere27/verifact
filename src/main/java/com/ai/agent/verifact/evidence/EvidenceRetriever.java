package com.ai.agent.verifact.evidence;

import com.ai.agent.verifact.common.ApiException;
import com.ai.agent.verifact.search.SearchProvider;
import com.ai.agent.verifact.search.SearchResult;
import com.ai.agent.verifact.search.SearchUnavailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Turns search queries into evidence the model may cite. Shared by every vertical (VeriFact checks,
 * LegalFact case intelligence): the backend runs the searches, never the model, and all content is
 * untrusted. Queries are grouped by topic (a claim, a legal issue) so each topic gets a fair share.
 */
@Component
public class EvidenceRetriever {

    private static final Logger log = LoggerFactory.getLogger(EvidenceRetriever.class);

    static final int RESULTS_PER_SEARCH = 4;
    static final int MAX_SNIPPET_CHARS = 600;
    static final int MAX_SOCIAL = 2;

    /** Search operators that could let content steer where evidence comes from (e.g. site:attacker.example). */
    private static final Pattern SEARCH_OPERATOR = Pattern.compile(
            "(?i)\\b(?:site|inurl|allinurl|intitle|allintitle|intext|allintext|filetype|ext|related|cache|link|info|source|before|after|daterange):\\S*");
    private static final Pattern EXCLUSION_TERM = Pattern.compile("(^|\\s)-\\S+");

    /**
     * @param maxSearches    total searches across all topics
     * @param maxEvidence    total evidence items kept
     * @param excludedSite   a registrable domain whose pages can't be evidence (the checked page's own site), or null
     * @param allowedDomains if not empty, only these domains (and their subdomains) are searched and kept
     */
    public record Options(int maxSearches, int maxEvidence, String excludedSite, List<String> allowedDomains) {

        public Options {
            allowedDomains = allowedDomains == null ? List.of() : List.copyOf(allowedDomains);
        }
    }

    private final SearchProvider searchProvider;

    public EvidenceRetriever(SearchProvider searchProvider) {
        this.searchProvider = searchProvider;
    }

    public String providerName() {
        return searchProvider.name();
    }

    /**
     * Runs each topic's first query, then second queries, within the search budget.
     *
     * @return evidence in retrieval order with provisional ids E1..En (callers may re-rank and renumber)
     * @throws ApiException 503 if every search failed: without search the only alternative would be the
     *                      model's memory, so the caller must refuse instead
     */
    public List<Evidence> retrieve(List<List<String>> queriesPerTopic, Options options, Instant now) {
        List<String> queries = new ArrayList<>();
        List<Integer> queryTopic = new ArrayList<>();
        for (int round = 0; round < 2; round++) {
            for (int i = 0; i < queriesPerTopic.size(); i++) {
                List<String> topicQueries = queriesPerTopic.get(i);
                if (topicQueries.size() > round && queries.size() < options.maxSearches()) {
                    queries.add(topicQueries.get(round));
                    queryTopic.add(i);
                }
            }
        }
        if (queries.isEmpty()) {
            return List.of();
        }
        int topics = queriesPerTopic.size();
        int perTopicCap = (options.maxEvidence() + topics - 1) / topics;
        int[] perTopic = new int[topics];

        Map<String, Evidence> byUrl = new LinkedHashMap<>();
        int failures = 0;
        int socialCount = 0;
        for (int qi = 0; qi < queries.size(); qi++) {
            int topic = queryTopic.get(qi);
            List<SearchResult> results;
            try {
                results = searchProvider.search(queries.get(qi), options.allowedDomains());
            } catch (SearchUnavailableException e) {
                log.warn("Search failed via {}: {}", searchProvider.name(), e.getMessage());
                failures++;
                continue;
            }
            int taken = 0;
            for (SearchResult r : results) {
                if (byUrl.size() >= options.maxEvidence() || taken >= RESULTS_PER_SEARCH || perTopic[topic] >= perTopicCap) {
                    break;
                }
                String key = Urls.normalizeUrl(r.url());
                String domain = Urls.domain(r.url());
                if (key == null || domain == null || byUrl.containsKey(key)) {
                    continue;
                }
                if (options.excludedSite() != null && options.excludedSite().equals(Urls.registrableDomain(domain))) {
                    continue; // the checked page (or its own site) can't be evidence for itself
                }
                if (!options.allowedDomains().isEmpty()
                        && options.allowedDomains().stream().noneMatch(allowed -> Urls.isOnDomain(domain, allowed))) {
                    continue; // enforced here too: not every provider can restrict domains
                }
                SourceType type = SourceType.classify(r.url(), domain, Urls.registrableDomain(domain));
                if (type == SourceType.SOCIAL && socialCount >= MAX_SOCIAL) {
                    continue; // user-generated content is kept to a minimum
                }
                if (type == SourceType.SOCIAL) {
                    socialCount++;
                }
                String id = "E" + (byUrl.size() + 1);
                byUrl.put(key, new Evidence(id, r.url(), domain, clean(r.title(), 300),
                        clean(r.snippet(), MAX_SNIPPET_CHARS), emptyToNull(clean(r.publishedDate(), 40)), now, type));
                taken++;
                perTopic[topic]++;
            }
        }
        if (failures == queries.size()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Web search is unavailable right now, so sources can't be gathered. Please try again later.");
        }
        return new ArrayList<>(byUrl.values());
    }

    /** Plain keywords only: search operators and exclusion terms are removed. */
    public static String cleanQuery(String query) {
        String q = clean(query, 200);
        q = SEARCH_OPERATOR.matcher(q).replaceAll(" ");
        q = EXCLUSION_TERM.matcher(q).replaceAll(" ");
        return q.replaceAll("\\s+", " ").trim();
    }

    private static String clean(String text, int max) {
        if (text == null) {
            return "";
        }
        String s = text.replaceAll("\\s+", " ").trim();
        return s.length() > max ? s.substring(0, max) : s;
    }

    private static String emptyToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
