package com.ai.agent.verifact.legal;

import java.util.List;
import java.util.regex.Pattern;

/**
 * What kind of official source a retrieved page is, from fixed URL rules (no model involvement).
 * Declaration order = display priority. Case law is not retrieved yet (ADR-12), so there is no COURT.
 */
public enum LegalSourceType {
    STATUTE, REGULATION, OFFICIAL_GUIDANCE, GOVERNMENT,
    /** Press releases, news pages and reports on official sites: context, not guidance. */
    NEWS_OR_REPORT;

    private static final Pattern NEWS_PATH = Pattern.compile(
            "/(?:news|newsroom|press|press-releases?|pressroom|opa/pr|dirnews|media|blog|reports?|crpt|speeches)(?:/|\\.|$)",
            Pattern.CASE_INSENSITIVE);

    private static final List<String> STATUTE_PREFIXES = List.of(
            "uscode.house.gov", "govinfo.gov/app/details/uscode", "govinfo.gov/content/pkg/uscode",
            "leginfo.legislature.ca.gov");
    private static final List<String> REGULATION_PREFIXES = List.of(
            "ecfr.gov", "federalregister.gov", "govinfo.gov/app/details/cfr", "govinfo.gov/content/pkg/cfr",
            "govinfo.gov/app/details/fr", "govinfo.gov/content/pkg/fr", "oal.ca.gov");
    private static final List<String> GUIDANCE_DOMAINS = List.of(
            "eeoc.gov", "dol.gov", "nlrb.gov", "osha.gov", "ftc.gov", "consumerfinance.gov", "hud.gov", "uscis.gov",
            "ssa.gov", "irs.gov", "dir.ca.gov", "calcivilrights.ca.gov", "edd.ca.gov", "dca.ca.gov", "courts.ca.gov");

    static LegalSourceType classify(String url, String domain) {
        String lower = url.toLowerCase(java.util.Locale.ROOT).replaceFirst("^https?://(www\\.)?", "");
        if (STATUTE_PREFIXES.stream().anyMatch(lower::startsWith)) {
            return STATUTE;
        }
        if (REGULATION_PREFIXES.stream().anyMatch(lower::startsWith)) {
            return REGULATION;
        }
        if (NEWS_PATH.matcher(lower).find()) {
            return NEWS_OR_REPORT;
        }
        if (GUIDANCE_DOMAINS.stream().anyMatch(d -> domain.equals(d) || domain.endsWith("." + d))) {
            return OFFICIAL_GUIDANCE;
        }
        return GOVERNMENT;
    }
}
