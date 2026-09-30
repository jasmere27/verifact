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
 * Output: target/content-audit/NN-domain.json and .md, plus summary.md. Up to 20 pages × (2 model
 * calls + ≤8 searches), plus 6 searches to find pages. Only the structural invariants are asserted;
 * whether flags are right is decided by reading the reports against the cited sources.
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
        Path out = Path.of("target", "content-audit");
        Files.createDirectories(out);

        Map<String, String> pages = new LinkedHashMap<>(); // domain → url, one page per site
        for (String q : FINDER_QUERIES) {
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
                audit = service.audit(page.getValue(), "CA");
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
        assertThat(n).as("pages audited").isGreaterThanOrEqualTo(5);
    }

    private static void assertInvariants(ContentAudit audit) {
        Set<String> ids = audit.sources().stream().map(CaseIntelligence.LegalSource::id).collect(Collectors.toSet());
        List<String> allowed = LegalSources.allowedDomains("CA");
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

    /** The per-page report as an editor would read it (the format for sample audits). */
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
