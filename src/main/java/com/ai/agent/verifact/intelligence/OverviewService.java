package com.ai.agent.verifact.intelligence;

import com.ai.agent.verifact.intelligence.billing.SubscriptionService;
import com.ai.agent.verifact.intelligence.incidents.Incident;
import com.ai.agent.verifact.intelligence.incidents.IncidentService;
import com.ai.agent.verifact.intelligence.investigations.Investigation;
import com.ai.agent.verifact.intelligence.investigations.InvestigationService;
import com.ai.agent.verifact.intelligence.monitoring.MonitoringProject;
import com.ai.agent.verifact.intelligence.monitoring.MonitoringProjectService;
import com.ai.agent.verifact.intelligence.monitoring.MonitoringRun;
import com.ai.agent.verifact.intelligence.monitoring.MonitoringRunner;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** The Overview dashboard: every number is counted from this workspace's stored data. */
@Service
public class OverviewService {

    public record SourceHealth(UUID projectId, String projectName, String brand, String health, MonitoringProject.Status status,
                               Instant lastRunAt, Instant lastSuccessAt, Instant nextRunAt, String lastRunStatus,
                               List<MonitoringRun.SourceOutcome> lastSources, String lastError) {}

    /**
     * @param onboarding true while the workspace has no projects and no investigations: the UI shows setup steps
     */
    public record Overview(SubscriptionService.Subscription subscription, SubscriptionService.Usage usage, int activeProjects,
                           int pausedProjects, int newMentions7d, int unreviewedMentions, int claimsFlagged7d, int incidentsNeedingReview,
                           int openIncidents, Map<String, Integer> openByPriority, List<Incident.Summary> topIncidents,
                           List<Investigation.Summary> recentInvestigations, List<MonitoringRun> recentRuns, List<SourceHealth> sourceHealth,
                           int evidenceRecords7d, int changedInvestigations7d, boolean onboarding) {}

    private final JdbcClient jdbc;
    private final SubscriptionService subscriptions;
    private final MonitoringProjectService projects;
    private final MonitoringRunner runner;
    private final IncidentService incidents;
    private final InvestigationService investigations;
    private final Clock clock;

    public OverviewService(JdbcClient jdbc, SubscriptionService subscriptions, MonitoringProjectService projects, MonitoringRunner runner,
                           IncidentService incidents, InvestigationService investigations, Clock clock) {
        this.jdbc = jdbc;
        this.subscriptions = subscriptions;
        this.projects = projects;
        this.runner = runner;
        this.incidents = incidents;
        this.investigations = investigations;
        this.clock = clock;
    }

    public Overview get(OrgContext ctx) {
        UUID org = ctx.orgId();
        Instant weekAgo = clock.instant().minus(Duration.ofDays(7));
        List<MonitoringProject> ps = projects.list(ctx);
        List<MonitoringRun> runs = runner.runs(ctx, null, 50);
        List<SourceHealth> health = ps.stream().map(p -> {
            MonitoringRun last = runs.stream().filter(r -> r.projectId().equals(p.id()) && !r.status().equals("RUNNING")).findFirst().orElse(null);
            return new SourceHealth(p.id(), p.name(), p.brand(), p.health(), p.status(), p.lastRunAt(), p.lastSuccessAt(), p.nextRunAt(),
                    last == null ? null : last.status(), last == null ? List.of() : last.sources(), last == null ? null : last.errorSummary());
        }).toList();
        int mentions7d = count("SELECT COUNT(*) FROM mentions WHERE organization_id = ? AND first_seen_at >= ?", org, Db.ts(weekAgo));
        int unreviewed = count("SELECT COUNT(*) FROM mentions WHERE organization_id = ? AND status = 'NEW'", org);
        int claims7d = count("SELECT COUNT(*) FROM mentions WHERE organization_id = ? AND first_seen_at >= ? AND claim IS NOT NULL", org, Db.ts(weekAgo));
        int review = count("SELECT COUNT(*) FROM incidents WHERE organization_id = ? AND status IN ('NEW', 'NEEDS_REVIEW')", org);
        int open = count("SELECT COUNT(*) FROM incidents WHERE organization_id = ? AND status <> 'RESOLVED'", org);
        Map<String, Integer> byPriority = new LinkedHashMap<>();
        for (String p : List.of("CRITICAL", "HIGH", "MEDIUM", "LOW")) {
            byPriority.put(p, count("SELECT COUNT(*) FROM incidents WHERE organization_id = ? AND status <> 'RESOLVED' AND priority = ?", org, p));
        }
        int evidence7d = count("SELECT COUNT(*) FROM evidence_records WHERE organization_id = ? AND retrieved_at >= ?", org, Db.ts(weekAgo));
        int changed7d = count("SELECT COUNT(*) FROM investigation_versions WHERE organization_id = ? AND created_at >= ? AND version > 1", org, Db.ts(weekAgo));
        Incident.Page top = incidents.list(ctx, new IncidentService.Filter("OPEN", null, null, null, null, "risk", 0, 5));
        Investigation.Page recent = investigations.list(ctx, null, 0, 5);
        boolean onboarding = ps.isEmpty() && recent.total() == 0 && open == 0;
        return new Overview(subscriptions.current(org), subscriptions.usage(org),
                (int) ps.stream().filter(p -> p.status() == MonitoringProject.Status.ACTIVE).count(),
                (int) ps.stream().filter(p -> p.status() == MonitoringProject.Status.PAUSED).count(),
                mentions7d, unreviewed, claims7d, review, open, byPriority, top.items(), recent.items(), runs.stream().limit(8).toList(),
                health, evidence7d, changed7d, onboarding);
    }

    private int count(String sql, Object... params) {
        return jdbc.sql(sql).params(params).query(Integer.class).single();
    }
}
