package com.ai.agent.verifact.legal;

import com.ai.agent.verifact.common.ApiException;
import com.ai.agent.verifact.evidence.Urls;
import com.ai.agent.verifact.legal.ContentAudit.AuditedStatement;
import com.ai.agent.verifact.legal.ContentAudit.Status;
import com.ai.agent.verifact.search.SearchProvider;
import com.ai.agent.verifact.search.SearchResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Experiment E2 (experiments.md): run the legal content audit on real California employment-law
 * pages from law-firm sites and save each report for human review of the flags. Costs money, so it
 * only runs with {@code RUN_EVALS=true} plus OPEN_AI_API_KEY and TAVILY_API_KEY:
 *
 * <pre>RUN_EVALS=true OPEN_AI_API_KEY=... TAVILY_API_KEY=... ./mvnw test -Dtest=ContentAuditEvalIT</pre>
 *
 * Output: target/content-audit/NN-domain.json, .md and .html (printable; the client-facing format),
 * plus summary.md. Up to 20 pages × (2 model calls + ≤8 searches), plus 6 searches to find pages.
 * Only the structural invariants are asserted; whether flags are right is decided by reading the
 * reports against the cited sources.
 *
 * <p>For a done-for-you audit of specific pages instead of the search-found sample:
 * {@code AUDIT_URLS="https://firm.example/a,https://firm.example/b" AUDIT_STATE=CA} (and optionally
 * {@code AUDIT_OUT=target/audit-clientname}).
 */
@SpringBootTest
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "RUN_EVALS", matches = "true")
class ContentAuditEvalIT {

    static final int MAX_PAGES = 20;

    /** Topics where California law changes often (rates, thresholds, new leave rules). */
    static final List<String> FINDER_QUERIES = List.of(
            "California minimum wage employment lawyer blog",
            "California overtime laws attorney practice area",
            "California final paycheck law employment attorney",
            "California meal and rest break laws law firm",
            "California paid sick leave law employment lawyer",
            "California exempt employee salary threshold attorney");

    /** Not law-firm content: government, big publishers, HR/payroll vendors, social, Q&A. */
    static final Set<String> NOT_FIRMS = Set.of(
            "ca.gov", "dol.gov", "wikipedia.org", "nolo.com", "findlaw.com", "justia.com", "lawinfo.com", "avvo.com",
            "legalmatch.com", "law.cornell.edu", "cornell.edu", "reddit.com", "youtube.com", "quora.com", "indeed.com",
            "adp.com", "paychex.com", "paycor.com", "gusto.com", "shrm.org", "calchamber.com", "hrdive.com",
            "linkedin.com", "facebook.com", "lawserver.com", "upcounsel.com", "martindale.com", "lawyers.com",
            "superlawyers.com", "thelaw.com", "casetext.com", "govdocs.com", "minimum-wage.org", "paylocity.com",
            "workforce.com", "rippling.com", "deel.com", "bamboohr.com", "laborlawcenter.com", "posterguard.com");

    @Autowired
    private ContentAuditService service;
    @Autowired
    private SearchProvider searchProvider;
    @Autowired
    private JsonMapper jsonMapper;

    @Test
    void auditRealPages() throws IOException {
        Path out = Path.of(System.getenv().getOrDefault("AUDIT_OUT", "target/content-audit"));
        Files.createDirectories(out);
        String state = System.getenv().getOrDefault("AUDIT_STATE", "CA");
        String given = System.getenv("AUDIT_URLS");

        Map<String, String> pages = new LinkedHashMap<>(); // key → url
        if (given != null && !given.isBlank()) {
            int i = 0;
            for (String url : given.split(",")) {
                if (!url.isBlank()) {
                    pages.put(String.format("%s-%d", Urls.registrableDomain(Urls.domain(url.trim())), ++i), url.trim());
                }
            }
        }
        for (String q : pages.isEmpty() ? FINDER_QUERIES : List.<String>of()) {
            for (SearchResult r : searchProvider.search(q)) {
                String domain = Urls.domain(r.url());
                String site = Urls.registrableDomain(domain);
                if (domain == null || domain.endsWith(".gov") || NOT_FIRMS.stream().anyMatch(n -> Urls.isOnDomain(domain, n))
                        || pages.containsKey(site)) {
                    continue;
                }
                pages.put(site, r.url());
            }
        }

        Map<Status, Integer> totals = new EnumMap<>(Status.class);
        List<String> summary = new ArrayList<>();
        int n = 0;
        for (Map.Entry<String, String> page : pages.entrySet()) {
            if (n == MAX_PAGES) {
                break;
            }
            ContentAudit audit;
            try {
                audit = service.audit(page.getValue(), state);
            } catch (ApiException e) {
                summary.add("| - | " + page.getKey() + " | fetch failed (" + e.getStatus().value() + ") | | | | |");
                continue;
            }
            n++;
            assertInvariants(audit);
            audit.statusCounts().forEach((k, v) -> totals.merge(k, v, Integer::sum));
            String name = String.format("%02d-%s", n, page.getKey().replaceAll("[^a-z0-9.-]", "_"));
            Files.writeString(out.resolve(name + ".json"), jsonMapper.writerWithDefaultPrettyPrinter().writeValueAsString(audit));
            Files.writeString(out.resolve(name + ".md"), markdown(audit));
            Files.writeString(out.resolve(name + ".html"), html(audit));
            Map<Status, Integer> c = audit.statusCounts();
            String line = String.format("| %02d | %s | %d | %d | %d | %d | %d |", n, page.getKey(),
                    c.get(Status.POTENTIALLY_OUTDATED), c.get(Status.POTENTIALLY_UNSUPPORTED), c.get(Status.REQUIRES_REVIEW),
                    c.get(Status.CONSISTENT_WITH_SOURCES), c.get(Status.UNABLE_TO_VERIFY));
            summary.add(line);
            System.out.println("AUDIT " + line);
        }
        String table = "| # | Site | Outdated? | Unsupported? | Review | Consistent | Unable |\n|---|---|---|---|---|---|---|\n"
                + String.join("\n", summary) + "\n\nTotals: " + totals + "\n";
        Files.writeString(out.resolve("summary.md"), table);
        System.out.println("\n" + table);
        assertThat(n).as("pages audited").isGreaterThanOrEqualTo(given == null || given.isBlank() ? 5 : 1);
    }

    private static void assertInvariants(ContentAudit audit) {
        Set<String> ids = audit.sources().stream().map(CaseIntelligence.LegalSource::id).collect(Collectors.toSet());
        List<String> allowed = LegalSources.allowedDomains(UsStates.code(System.getenv().getOrDefault("AUDIT_STATE", "CA")));
        assertThat(audit.sources()).allSatisfy(s ->
                assertThat(allowed).anySatisfy(d -> assertThat(Urls.isOnDomain(s.domain(), d)).isTrue()));
        for (AuditedStatement s : audit.statements()) {
            assertThat(ids).containsAll(s.sourceIds());
            if (s.status() != Status.UNABLE_TO_VERIFY) {
                assertThat(s.sourceIds()).as("a status needs a source").isNotEmpty();
            }
            if (s.status() == Status.POTENTIALLY_OUTDATED || s.status() == Status.POTENTIALLY_UNSUPPORTED
                    || s.status() == Status.CONSISTENT_WITH_SOURCES) {
                assertThat(s.sourceSays()).as("a finding shows what the source says").isNotNull();
            }
            assertThat(AdviceLanguage.isAdvice(s.note())).isFalse();
        }
    }

    private static final Map<Status, String[]> STATUS_STYLE = Map.of(
            Status.POTENTIALLY_OUTDATED, new String[]{"Potentially outdated", "#8f5100"},
            Status.POTENTIALLY_UNSUPPORTED, new String[]{"Potentially unsupported", "#b0261c"},
            Status.REQUIRES_REVIEW, new String[]{"Requires review", "#6a4596"},
            Status.CONSISTENT_WITH_SOURCES, new String[]{"Consistent with official sources", "#1c6b3f"},
            Status.UNABLE_TO_VERIFY, new String[]{"Unable to verify", "#545b66"});

    private static String esc(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    /**
     * The client-facing report: one self-contained, printable HTML page (print to PDF from a browser).
     * Flags first, then items needing review, then the rest; every flag shows the page's words, the
     * official source's words, and a link.
     */
    static String html(ContentAudit a) {
        Map<String, CaseIntelligence.LegalSource> byId = new LinkedHashMap<>();
        a.sources().forEach(s -> byId.put(s.id(), s));
        List<AuditedStatement> ordered = new ArrayList<>(a.statements());
        ordered.sort(java.util.Comparator.comparingInt(s -> List.of(Status.POTENTIALLY_OUTDATED, Status.POTENTIALLY_UNSUPPORTED,
                Status.REQUIRES_REVIEW, Status.CONSISTENT_WITH_SOURCES, Status.UNABLE_TO_VERIFY).indexOf(s.status())));
        Map<Status, Integer> c = a.statusCounts();
        StringBuilder h = new StringBuilder("""
                <!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
                <title>Legal content audit</title><style>
                body{font:15px/1.55 system-ui,-apple-system,"Segoe UI",Roboto,Arial,sans-serif;color:#121626;background:#fff;margin:0}
                main{max-width:820px;margin:0 auto;padding:32px 20px 48px}
                h1{font:600 26px/1.2 Georgia,serif;margin:0 0 4px}.muted{color:#586079}.small{font-size:13px}
                .notice{border-left:4px solid #8f5100;background:#fbf6ee;padding:10px 14px;margin:16px 0;font-size:13px}
                .counts{display:flex;flex-wrap:wrap;gap:8px;margin:16px 0}.count{border:1px solid #e1e5ef;border-radius:8px;padding:6px 10px;font-size:13px}
                .item{border:1px solid #e1e5ef;border-left:4px solid var(--c);border-radius:10px;padding:14px 16px;margin:12px 0;break-inside:avoid}
                .status{font-weight:700;color:var(--c);font-size:13px;text-transform:uppercase;letter-spacing:.04em}
                .quote{border-left:2px solid #c7cddc;padding-left:10px;font-family:Georgia,serif;margin:6px 0}
                a{color:#2b4fd6}footer{margin-top:28px;font-size:12px;color:#586079}
                @media print{main{padding:0}}
                </style></head><body><main>
                """);
        h.append("<p class=\"muted small\">LegalFact · Legal content audit</p><h1>").append(esc(a.pageTitle())).append("</h1>");
        h.append("<p class=\"small\"><a href=\"").append(esc(a.url())).append("\">").append(esc(a.url())).append("</a><br>");
        h.append("Checked ").append(a.checkedAt().toString().substring(0, 10)).append(" · ").append(esc(a.jurisdiction())).append("</p>");
        h.append("<div class=\"counts\">");
        for (Status s : List.of(Status.POTENTIALLY_OUTDATED, Status.POTENTIALLY_UNSUPPORTED, Status.REQUIRES_REVIEW,
                Status.CONSISTENT_WITH_SOURCES, Status.UNABLE_TO_VERIFY)) {
            h.append("<span class=\"count\" style=\"--c:").append(STATUS_STYLE.get(s)[1]).append("\"><b>").append(c.get(s))
                    .append("</b> ").append(STATUS_STYLE.get(s)[0].toLowerCase(java.util.Locale.ROOT)).append("</span>");
        }
        h.append("</div><p class=\"notice\">").append(esc(a.notice())).append("</p>");
        for (AuditedStatement s : ordered) {
            String[] style = STATUS_STYLE.get(s.status());
            h.append("<section class=\"item\" style=\"--c:").append(style[1]).append("\"><div class=\"status\">")
                    .append(style[0]).append("</div>");
            h.append("<p><b>Page says:</b></p><p class=\"quote\">“").append(esc(s.pageQuote())).append("”</p>");
            if (s.sourceSays() != null) {
                h.append("<p><b>Official source says:</b> ").append(esc(s.sourceSays())).append("</p>");
            }
            if (s.note() != null) {
                h.append("<p class=\"muted\">").append(esc(s.note())).append("</p>");
            }
            for (String id : s.sourceIds()) {
                CaseIntelligence.LegalSource src = byId.get(id);
                h.append("<p class=\"small\">Source: <a href=\"").append(esc(src.url())).append("\">").append(esc(src.title()))
                        .append("</a> (").append(esc(src.domain())).append(")</p>");
            }
            h.append("</section>");
        }
        if (!a.limitations().isEmpty()) {
            h.append("<h2 style=\"font-size:16px\">Limitations</h2><ul>");
            a.limitations().forEach(l -> h.append("<li>").append(esc(l)).append("</li>"));
            h.append("</ul>");
        }
        h.append("<footer>Sources are official government websites only (statutes, regulations, agency guidance). ")
                .append("Case law is not included. Please have an attorney confirm any change before publishing.</footer></main></body></html>");
        return h.toString();
    }

    /** The per-page report as an editor would read it (Markdown). */
    static String markdown(ContentAudit a) {
        Map<String, CaseIntelligence.LegalSource> byId = new LinkedHashMap<>();
        a.sources().forEach(s -> byId.put(s.id(), s));
        StringBuilder md = new StringBuilder("# Legal content audit\n\n");
        md.append("**Page:** ").append(a.pageTitle()).append("  \n").append(a.url()).append("\n\n");
        md.append("**Checked:** ").append(a.checkedAt()).append(" · **Jurisdiction:** ").append(a.jurisdiction()).append("\n\n");
        md.append("> ").append(a.notice()).append("\n\n");
        for (AuditedStatement s : a.statements()) {
            md.append("## ").append(s.id()).append(" · ").append(s.status().name().replace('_', ' ')).append("\n\n");
            md.append("**Page says:** “").append(s.pageQuote()).append("”\n\n");
            if (s.sourceSays() != null) {
                md.append("**Official source says:** ").append(s.sourceSays()).append("\n\n");
            }
            if (s.note() != null) {
                md.append("**Note:** ").append(s.note()).append("\n\n");
            }
            for (String id : s.sourceIds()) {
                CaseIntelligence.LegalSource src = byId.get(id);
                md.append("- ").append(id).append(": [").append(src.title()).append("](").append(src.url()).append(")\n");
            }
            md.append('\n');
        }
        if (!a.limitations().isEmpty()) {
            md.append("## Limitations\n\n");
            a.limitations().forEach(l -> md.append("- ").append(l).append('\n'));
        }
        return md.toString();
    }
}
