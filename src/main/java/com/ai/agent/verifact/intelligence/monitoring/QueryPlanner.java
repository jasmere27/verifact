package com.ai.agent.verifact.intelligence.monitoring;

import com.ai.agent.verifact.evidence.EvidenceRetriever;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Bounded search queries for one run: the brand alone, the brand with each concern (keywords, then topics), then
 * products and executives with the brand. Plain keywords only; search operators are stripped.
 */
public final class QueryPlanner {

    private QueryPlanner() {
    }

    public static List<String> queries(String brand, MonitoringConfig config, int max) {
        Set<String> out = new LinkedHashSet<>();
        String b = quoted(brand);
        out.add(b);
        for (String alias : config.aliases()) {
            out.add(quoted(alias));
        }
        List<String> concerns = new ArrayList<>(config.keywords());
        concerns.addAll(config.topics());
        for (String c : concerns) {
            out.add(b + " " + c);
        }
        for (String p : config.products()) {
            out.add(b + " " + p);
        }
        for (String e : config.executives()) {
            out.add(quoted(e) + " " + b);
        }
        return out.stream().map(EvidenceRetriever::cleanQuery).filter(q -> !q.isBlank()).limit(Math.max(1, max)).toList();
    }

    /** Multi-word names as exact phrases. */
    private static String quoted(String term) {
        String t = term.replace("\"", "").trim();
        return t.contains(" ") ? "\"" + t + "\"" : t;
    }
}
