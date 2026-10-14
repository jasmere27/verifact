package com.ai.agent.verifact.intelligence.reports;

import com.ai.agent.verifact.account.Role;
import com.ai.agent.verifact.ai.UsageScope;
import com.ai.agent.verifact.common.ApiException;
import com.ai.agent.verifact.intelligence.AuditLog;
import com.ai.agent.verifact.intelligence.Db;
import com.ai.agent.verifact.intelligence.OrgContext;
import com.ai.agent.verifact.intelligence.Text;
import com.ai.agent.verifact.intelligence.billing.Feature;
import com.ai.agent.verifact.intelligence.billing.SubscriptionService;
import com.ai.agent.verifact.intelligence.investigations.Investigation;
import com.ai.agent.verifact.intelligence.monitoring.MonitoringConfig;
import com.ai.agent.verifact.intelligence.workspace.WorkspaceService;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** Builds, stores and lists period reports from the workspace's own data. */
@Service
public class ReportService {

    static final int MAX_PERIOD_DAYS = 92;
    static final String DISCLAIMER = "Automated report from VeriFact Intelligence. Figures count what this workspace's monitoring and "
            + "investigations recorded; they are not audience or reach estimates. Assessments rest on the cited sources available when "
            + "they were retrieved and can change. AI readings are labelled. Review before relying on it.";
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH).withZone(ZoneOffset.UTC);

    public record GenerateInput(Integer days, String title) {}

    private final JdbcClient jdbc;
    private final JsonMapper json;
    private final SubscriptionService subscriptions;
    private final AuditLog audit;
    private final Clock clock;

    public ReportService(JdbcClient jdbc, JsonMapper json, SubscriptionService subscriptions, AuditLog audit, Clock clock) {
        this.jdbc = jdbc;
        this.json = json;
        this.subscriptions = subscriptions;
        this.audit = audit;
        this.clock = clock;
    }

    public Report generate(OrgContext ctx, GenerateInput in) {
        int days = in == null || in.days() == null ? 30 : in.days();
        if (days < 1 || days > MAX_PERIOD_DAYS) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Choose a period of 1 to " + MAX_PERIOD_DAYS + " days.");
        }
        UUID usage = subscriptions.reserve(ctx, Feature.REPORT);
        Instant end = clock.instant();
        Instant start = end.minus(Duration.ofDays(days));
        String title = in == null ? null : Text.clean(in.title(), 200);
        Report report = UsageScope.measure(() -> build(ctx, start, end, title), t -> subscriptions.recordCost(usage, t));
        jdbc.sql("INSERT INTO intelligence_reports (id, organization_id, title, period_start, period_end, report_json, created_by, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)")
                .params(report.id(), ctx.orgId(), report.title(), Db.ts(start), Db.ts(end), json.writeValueAsString(report), ctx.userId(), Db.ts(end))
                .update();
        audit.record(ctx, "report.generated", "report", report.id(), report.title());
        return report;
    }

    public List<Report.Summary> list(OrgContext ctx) {
        return jdbc.sql("SELECT r.id, r.title, r.period_start, r.period_end, r.created_at, u.email FROM intelligence_reports r "
                        + "LEFT JOIN app_users u ON u.id = r.created_by WHERE r.organization_id = ? ORDER BY r.created_at DESC LIMIT 100")
                .param(ctx.orgId())
                .query((rs, i) -> new Report.Summary(Db.uuid(rs, "id"), rs.getString("title"), Db.instant(rs, "period_start"),
                        Db.instant(rs, "period_end"), Db.instant(rs, "created_at"), rs.getString("email")))
                .list();
    }

    public Report get(OrgContext ctx, UUID id) {
        return jdbc.sql("SELECT report_json FROM intelligence_reports WHERE organization_id = ? AND id = ?").params(ctx.orgId(), id)
                .query(String.class).optional().map(j -> json.readValue(j, Report.class))
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "That report doesn't exist (it may have been deleted)."));
    }

    public void delete(OrgContext ctx, UUID id) {
        WorkspaceService.requireRole(ctx, Role.ADMIN);
        Report r = get(ctx, id);
        jdbc.sql("DELETE FROM intelligence_reports WHERE organization_id = ? AND id = ?").params(ctx.orgId(), id).update();
        audit.record(ctx, "report.deleted", "report", id, r.title());
    }

    // ---------------------------------------------------------------- building

    Report build(OrgContext ctx, Instant start, Instant end, String customTitle) {
        UUID org = ctx.orgId();
        String workspace = jdbc.sql("SELECT name FROM organizations WHERE id = ?").param(org).query(String.class).single();
        String by = ctx.userId() == null ? null : jdbc.sql("SELECT email FROM app_users WHERE id = ?").param(ctx.userId()).query(String.class).optional().orElse(null);

        // Coverage
        record P(UUID id, String name, String brand, String status, MonitoringConfig config, Instant lastSuccess) {}
        List<P> projects = jdbc.sql("SELECT id, name, brand, status, config_json, last_success_at FROM monitoring_projects WHERE organization_id = ? ORDER BY created_at")
                .param(org).query((rs, i) -> new P(Db.uuid(rs, "id"), rs.getString("name"), rs.getString("brand"), rs.getString("status"),
                        json.readValue(rs.getString("config_json"), MonitoringConfig.class), Db.instant(rs, "last_success_at"))).list();
        record R(UUID project, String status, String sources) {}
        List<R> runs = jdbc.sql("SELECT project_id, status, sources_json FROM monitoring_runs WHERE organization_id = ? AND started_at >= ? AND started_at <= ?")
                .params(org, Db.ts(start), Db.ts(end)).query((rs, i) -> new R(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3))).list();
        List<Report.ProjectCoverage> coverage = projects.stream().map(p -> new Report.ProjectCoverage(p.name(), p.brand(), p.status(), sources(p.config()),
                (int) runs.stream().filter(r -> r.project().equals(p.id())).count(),
                (int) runs.stream().filter(r -> r.project().equals(p.id()) && r.status().equals("FAILED")).count(), p.lastSuccess())).toList();
        int failedRuns = (int) runs.stream().filter(r -> r.status().equals("FAILED")).count();
        int partialRuns = (int) runs.stream().filter(r -> r.status().equals("PARTIAL")).count();

        // Mentions
        record M(String title, String url, String domain, String date, Instant seen, String relevance, String claim, String connector, String matched) {}
        List<M> mentions = jdbc.sql("SELECT title, url, domain, published_date, first_seen_at, relevance, claim, connector, matched_json FROM mentions "
                        + "WHERE organization_id = ? AND first_seen_at >= ? AND first_seen_at <= ? ORDER BY first_seen_at DESC")
                .params(org, Db.ts(start), Db.ts(end))
                .query((rs, i) -> new M(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), Db.instant(rs, 5), rs.getString(6),
                        rs.getString(7), rs.getString(8), rs.getString(9))).list();
        Map<String, Integer> byRelevance = new LinkedHashMap<>();
        for (String l : List.of("HIGH", "MEDIUM", "LOW")) {
            byRelevance.put(l, (int) mentions.stream().filter(m -> l.equals(m.relevance())).count());
        }
        Map<String, Integer> bySource = new LinkedHashMap<>();
        mentions.forEach(m -> bySource.merge(m.connector(), 1, Integer::sum));
        int withClaims = (int) mentions.stream().filter(m -> m.claim() != null).count();
        List<Report.MentionLine> important = mentions.stream().filter(m -> m.claim() != null || "HIGH".equals(m.relevance()))
                .sorted(Comparator.comparing((M m) -> m.claim() == null ? 1 : 0).thenComparing(M::seen, Comparator.reverseOrder()))
                .limit(10).map(m -> new Report.MentionLine(m.title(), m.url(), m.domain(), m.date(), m.seen(), m.relevance(), m.claim())).toList();

        // Themes: the monitored concern terms that new mentions matched most often.
        Map<String, Integer> termCounts = new HashMap<>();
        java.util.Set<String> concernTerms = new java.util.HashSet<>();
        projects.forEach(p -> {
            p.config().keywords().forEach(t -> concernTerms.add(t.toLowerCase(Locale.ROOT)));
            p.config().topics().forEach(t -> concernTerms.add(t.toLowerCase(Locale.ROOT)));
        });
        for (M m : mentions) {
            List<String> matched = json.readValue(m.matched(), new TypeReference<List<String>>() {});
            matched.stream().filter(t -> concernTerms.contains(t.toLowerCase(Locale.ROOT))).distinct().forEach(t -> termCounts.merge(t, 1, Integer::sum));
        }
        List<Report.Theme> themes = termCounts.entrySet().stream().sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .limit(8).map(e -> new Report.Theme(e.getKey(), e.getValue())).toList();

        // Incidents
        record I(String title, String priority, int score, String status, String owner, Instant first, Instant created, Instant resolved, int mentions) {}
        List<I> incidents = jdbc.sql("SELECT i.title, i.priority, i.risk_score, i.status, u.email, i.first_seen_at, i.created_at, i.resolved_at, "
                        + "(SELECT COUNT(*) FROM mentions m WHERE m.incident_id = i.id AND m.organization_id = i.organization_id) "
                        + "FROM incidents i LEFT JOIN app_users u ON u.id = i.owner_id WHERE i.organization_id = ?")
                .param(org).query((rs, i) -> new I(rs.getString(1), rs.getString(2), rs.getInt(3), rs.getString(4), rs.getString(5),
                        Db.instant(rs, 6), Db.instant(rs, 7), Db.instant(rs, 8), rs.getInt(9))).list();
        int opened = (int) incidents.stream().filter(i -> !i.created().isBefore(start) && !i.created().isAfter(end)).count();
        int resolved = (int) incidents.stream().filter(i -> i.resolved() != null && !i.resolved().isBefore(start) && !i.resolved().isAfter(end)).count();
        List<I> open = incidents.stream().filter(i -> !i.status().equals("RESOLVED")).toList();
        Map<String, Integer> byPriority = new LinkedHashMap<>();
        for (String p : List.of("CRITICAL", "HIGH", "MEDIUM", "LOW")) {
            byPriority.put(p, (int) open.stream().filter(i -> i.priority().equals(p)).count());
        }
        Map<String, Integer> byStatus = new LinkedHashMap<>();
        open.forEach(i -> byStatus.merge(i.status(), 1, Integer::sum));
        List<Report.IncidentLine> openLines = open.stream().sorted(Comparator.comparingInt(I::score).reversed()).limit(10)
                .map(i -> new Report.IncidentLine(i.title(), i.priority(), i.score(), i.status(), i.owner(), i.first(), i.mentions())).toList();

        // Investigation findings and evidence changes within the period
        record V(UUID id, String title, int version, Instant at, String result, String changes) {}
        List<V> versions = jdbc.sql("SELECT v.id, v.title, iv.version, iv.created_at, iv.result_json, iv.changes_json FROM investigation_versions iv "
                        + "JOIN investigations v ON v.id = iv.investigation_id AND v.organization_id = iv.organization_id "
                        + "WHERE iv.organization_id = ? AND iv.created_at >= ? AND iv.created_at <= ? ORDER BY iv.created_at DESC")
                .params(org, Db.ts(start), Db.ts(end))
                .query((rs, i) -> new V(Db.uuid(rs, "id"), rs.getString(2), rs.getInt(3), Db.instant(rs, 4), rs.getString(5), rs.getString(6))).list();
        List<Report.Finding> findings = new ArrayList<>();
        List<Report.EvidenceChange> changes = new ArrayList<>();
        List<String> unresolved = new ArrayList<>();
        java.util.Set<UUID> seenInvestigations = new java.util.HashSet<>();
        for (V v : versions) {
            Investigation.Result r = json.readValue(v.result(), Investigation.Result.class);
            if (v.changes() != null) {
                Investigation.Changes c = json.readValue(v.changes(), Investigation.Changes.class);
                changes.add(new Report.EvidenceChange(v.title(), v.version(), v.at(), c.summary()));
            }
            if (!seenInvestigations.add(v.id())) {
                continue; // latest version per investigation only
            }
            for (Investigation.ClaimResult c : r.claims()) {
                Investigation.Excerpt ex = c.supporting() != null ? c.supporting() : c.contradicting();
                if (c.assessment() == Investigation.Assessment.CONTRADICTED && c.contradicting() != null) {
                    ex = c.contradicting();
                }
                findings.add(new Report.Finding(v.title(), v.version(), c.claim(), c.assessment().name(), ex == null ? null : ex.text(),
                        ex == null ? null : ex.url(), ex == null ? null : ex.domain(), v.at()));
            }
            r.unresolved().stream().limit(3).forEach(u -> unresolved.add(v.title() + ": " + u));
        }
        open.stream().filter(i -> i.status().equals("AWAITING_EVIDENCE")).forEach(i -> unresolved.add("Incident awaiting evidence: " + i.title()));

        // Actions and limitations (rules over the numbers above)
        List<String> actions = new ArrayList<>();
        long criticalUnowned = open.stream().filter(i -> (i.priority().equals("CRITICAL") || i.priority().equals("HIGH")) && i.owner() == null).count();
        if (criticalUnowned > 0) {
            actions.add("Assign owners to " + criticalUnowned + " high or critical open incident" + (criticalUnowned == 1 ? "" : "s") + ".");
        }
        long uninvestigated = jdbc.sql("SELECT COUNT(*) FROM incidents WHERE organization_id = ? AND status <> 'RESOLVED' AND investigation_id IS NULL")
                .param(org).query(Long.class).single();
        if (uninvestigated > 0) {
            actions.add("Start investigations for " + uninvestigated + " open incident" + (uninvestigated == 1 ? "" : "s") + " without one.");
        }
        long supported = findings.stream().filter(f -> f.assessment().equals("SUPPORTED")).count();
        if (supported > 0) {
            actions.add("Review " + supported + " claim" + (supported == 1 ? "" : "s") + " that available evidence supports with leadership before responding.");
        }
        if (failedRuns > 0) {
            actions.add("Check monitoring source health: " + failedRuns + " run" + (failedRuns == 1 ? "" : "s") + " failed in this period.");
        }
        if (projects.isEmpty()) {
            actions.add("Set up a monitoring project so future reports include monitoring coverage.");
        }
        List<String> limitations = new ArrayList<>(List.of(
                "Coverage is limited to the sources configured per project (web and news search via the search provider, YouTube video "
                        + "titles and descriptions, and customer-supplied RSS feeds and pages). Other social media platforms (X, Facebook, "
                        + "Instagram, TikTok, Reddit) are not monitored.",
                "Monitoring sees search snippets and feed summaries, not full articles or paywalled content.",
                "Counts reflect what monitoring retrieved; they are not estimates of audience, reach or engagement."));
        if (failedRuns + partialRuns > 0) {
            limitations.add(failedRuns + " failed and " + partialRuns + " partially failed run(s) in this period may have missed mentions.");
        }
        projects.stream().filter(p -> p.status().equals("PAUSED")).forEach(p -> limitations.add("Project \"" + p.name() + "\" is paused."));

        List<String> summary = new ArrayList<>();
        summary.add("From " + DAY.format(start) + " to " + DAY.format(end) + ", monitoring ran " + runs.size() + " time" + (runs.size() == 1 ? "" : "s")
                + " across " + projects.size() + " project" + (projects.size() == 1 ? "" : "s") + " and recorded " + mentions.size()
                + " new mention" + (mentions.size() == 1 ? "" : "s") + " (" + byRelevance.get("HIGH") + " high relevance, " + withClaims
                + " with a potentially damaging claim identified by AI).");
        summary.add(opened + " incident" + (opened == 1 ? " was" : "s were") + " opened and " + resolved + " resolved; " + open.size()
                + " remain open (" + byPriority.get("CRITICAL") + " critical, " + byPriority.get("HIGH") + " high).");
        if (!findings.isEmpty()) {
            Map<String, Long> counts = new LinkedHashMap<>();
            findings.forEach(f -> counts.merge(f.assessment(), 1L, Long::sum));
            summary.add("Investigations assessed " + findings.size() + " claim" + (findings.size() == 1 ? "" : "s") + ": "
                    + counts.entrySet().stream().map(e -> e.getValue() + " " + e.getKey().toLowerCase(Locale.ROOT).replace('_', ' '))
                    .reduce((a, b) -> a + ", " + b).orElse("") + ".");
        } else {
            summary.add("No investigations completed in this period.");
        }
        String title = customTitle != null ? customTitle : workspace + " · Intelligence report · " + DAY.format(start) + " – " + DAY.format(end);
        return new Report(UUID.randomUUID(), title, workspace, start, end, end, by, summary,
                new Report.Coverage(coverage, runs.size(), failedRuns, partialRuns),
                new Report.MentionStats(mentions.size(), byRelevance, bySource, withClaims), important,
                new Report.IncidentStats(opened, resolved, open.size(), byPriority, byStatus), openLines,
                findings.stream().limit(30).toList(), themes, changes.stream().limit(20).toList(), actions,
                unresolved.stream().limit(15).toList(), limitations, DISCLAIMER);
    }

    private static List<String> sources(MonitoringConfig c) {
        List<String> s = new ArrayList<>();
        if (c.sources().webSearch()) {
            s.add("Web search");
        }
        if (c.sources().newsSearch()) {
            s.add("News search");
        }
        if (c.sources().youtube()) {
            s.add("YouTube");
        }
        if (!c.sources().rssFeeds().isEmpty()) {
            s.add(c.sources().rssFeeds().size() + " RSS feed(s)");
        }
        if (!c.sources().pages().isEmpty()) {
            s.add(c.sources().pages().size() + " watched page(s)");
        }
        return s;
    }
}
