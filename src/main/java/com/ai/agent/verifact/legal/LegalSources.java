package com.ai.agent.verifact.legal;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The official domains LegalFact may retrieve from (ADR-12; researched 2026-09-30, see legalfact.md).
 * Search is restricted to these, so every "potentially relevant source" is an official one.
 * Cornell LII (non-commercial licence) and case law (CourtListener needs a commercial agreement) are
 * deliberately absent.
 */
final class LegalSources {

    private LegalSources() {
    }

    static final List<String> FEDERAL = List.of(
            "uscode.house.gov", "govinfo.gov", "ecfr.gov", "federalregister.gov", "congress.gov",
            "eeoc.gov", "dol.gov", "nlrb.gov", "osha.gov", "ftc.gov", "consumerfinance.gov", "hud.gov",
            "uscis.gov", "justice.gov", "ssa.gov", "irs.gov", "usa.gov");

    /** States with curated official domains. Others get federal sources only, and the report says so. */
    static final Map<String, List<String>> STATE = Map.of(
            "CA", List.of("leginfo.legislature.ca.gov", "dir.ca.gov", "calcivilrights.ca.gov", "edd.ca.gov",
                    "dca.ca.gov", "courts.ca.gov", "oag.ca.gov", "oal.ca.gov"));

    /** Bill texts, version comparisons and bill status pages: proposals or history, not current law. */
    private static final java.util.regex.Pattern BILL_PAGE = java.util.regex.Pattern.compile(
            "(?i)(leginfo\\.legislature\\.ca\\.gov/faces/bill|congress\\.gov/bill/|govinfo\\.gov/(?:app/details|content/pkg)/(?:bills|billstatus))");

    static boolean isCurrentLawSource(String url) {
        return url != null && !BILL_PAGE.matcher(url).find();
    }

    static List<String> allowedDomains(String stateCode) {
        List<String> domains = new ArrayList<>(FEDERAL);
        if (stateCode != null) {
            domains.addAll(STATE.getOrDefault(stateCode, List.of()));
        }
        return domains;
    }

    static boolean hasStateSources(String stateCode) {
        return stateCode != null && STATE.containsKey(stateCode);
    }
}
