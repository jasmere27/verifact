package com.ai.agent.verifact.intelligence.monitoring;

import java.util.ArrayList;
import java.util.List;

/**
 * What a monitoring project watches (stored as JSON). Entity terms (brand, aliases, products, executives) decide
 * whether an item is about the brand at all; keywords and topics of concern raise its relevance; excluded terms
 * drop it.
 *
 * @param lookbackDays how far back search connectors look on each run (1, 7 or 30)
 * @param alertThreshold the lowest claim severity that opens an incident automatically: HIGH, MEDIUM, or NONE (never)
 */
public record MonitoringConfig(List<String> aliases, List<String> products, List<String> executives, List<String> keywords,
                               List<String> topics, List<String> excludedTerms, Sources sources, int lookbackDays,
                               String alertThreshold) {

    /** Which connectors run. Feeds and pages are public URLs supplied by the customer. */
    public record Sources(boolean webSearch, boolean newsSearch, List<String> rssFeeds, List<String> pages) {

        public Sources {
            rssFeeds = rssFeeds == null ? List.of() : List.copyOf(rssFeeds);
            pages = pages == null ? List.of() : List.copyOf(pages);
        }
    }

    public MonitoringConfig {
        aliases = nonNull(aliases);
        products = nonNull(products);
        executives = nonNull(executives);
        keywords = nonNull(keywords);
        topics = nonNull(topics);
        excludedTerms = nonNull(excludedTerms);
        sources = sources == null ? new Sources(true, true, List.of(), List.of()) : sources;
    }

    /** Brand plus every name that identifies it. */
    public List<String> entityTerms(String brand) {
        List<String> all = new ArrayList<>();
        all.add(brand);
        all.addAll(aliases);
        all.addAll(products);
        all.addAll(executives);
        return all;
    }

    /** Terms counted against the plan's per-project limit. */
    public int termCount() {
        return aliases.size() + products.size() + executives.size() + keywords.size() + topics.size() + excludedTerms.size();
    }

    private static List<String> nonNull(List<String> l) {
        return l == null ? List.of() : List.copyOf(l);
    }
}
