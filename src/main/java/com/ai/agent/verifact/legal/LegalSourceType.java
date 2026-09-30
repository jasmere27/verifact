package com.ai.agent.verifact.legal;

import java.util.List;

/**
 * What kind of official source a retrieved page is, from fixed URL rules (no model involvement).
 * Declaration order = display priority. Case law is not retrieved yet (ADR-12), so there is no COURT.
 */
public enum LegalSourceType {
    STATUTE, REGULATION, OFFICIAL_GUIDANCE, GOVERNMENT;

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
        if (GUIDANCE_DOMAINS.stream().anyMatch(d -> domain.equals(d) || domain.endsWith("." + d))) {
            return OFFICIAL_GUIDANCE;
        }
        return GOVERNMENT;
    }
}
