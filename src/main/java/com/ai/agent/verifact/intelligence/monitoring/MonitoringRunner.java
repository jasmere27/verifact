package com.ai.agent.verifact.intelligence.monitoring;

import com.ai.agent.verifact.ai.UsageScope;
import com.ai.agent.verifact.common.ApiException;
import com.ai.agent.verifact.intelligence.Db;
import com.ai.agent.verifact.intelligence.OrgContext;
import com.ai.agent.verifact.intelligence.Text;
import com.ai.agent.verifact.intelligence.billing.Feature;
import com.ai.agent.verifact.intelligence.billing.SubscriptionService;
import com.ai.agent.verifact.intelligence.incidents.IncidentService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Runs a monitoring project: claim the project (a lease, so two instances or a manual and a scheduled run never
 * overlap), check the plan and meter the run, query each enabled connector, keep relevant items as de-duplicated
 * mentions, extract claims from new ones, and open or link incidents. Every run is persisted with per-source
 * outcomes; failures are retried with backoff and never hidden.
 */
@Service
public class MonitoringRunner {

    private static final Logger log = LoggerFactory.getLogger(MonitoringRunner.class);

    static final Duration LEASE = Duration.ofMinutes(30);
    static final int MAX_ITEMS_PER_CONNECTOR = 100;
    static final int MAX_RETRIES = 3;
    static final Duration FIRST_RETRY = Duration.ofMinutes(15);

    public enum Trigger { SCHEDULED, MANUAL, RETRY }

    private final JdbcClient jdbc;
    private final JsonMapper json;
    private final MonitoringProjectService projects;
    private final MentionStore mentions;
    private final MentionClaimExtractor extractor;
    private final IncidentService incidents;
    private final SubscriptionService subscriptions;
    private final List<SourceConnector> connectors;
    private final Clock clock;
    private final int maxQueries;
    private final ExecutorService pool;

    public MonitoringRunner(JdbcClient jdbc, JsonMapper json, MonitoringProjectService projects, MentionStore mentions,
                            MentionClaimExtractor extractor, IncidentService incidents, SubscriptionService subscriptions,
                            List<SourceConnector> connectors, Clock clock,
                            @Value("${app.monitoring.max-queries-per-connector:4}") int maxQueries,
                            @Value("${app.monitoring.concurrency:2}") int concurrency) {
        this.jdbc = jdbc;
        this.json = json;
        this.projects = projects;
        this.mentions = mentions;
        this.extractor = extractor;
        this.incidents = incidents;
        this.subscriptions = subscriptions;
        this.connectors = connectors;
        this.clock = clock;
        this.maxQueries = maxQueries;
        this.pool = Executors.newFixedThreadPool(Math.max(1, concurrency), r -> {
            Thread t = new Thread(r, "monitoring");
            t.setDaemon(true);
            return t;
        });
    }

    /** A person asks for a run now: checked and leased synchronously, executed in the background. */
    public MonitoringRun runNow(OrgContext ctx, UUID projectId) {
        MonitoringProject p = projects.get(ctx, projectId);
        if (!claim(ctx.orgId(), projectId, false)) {
            throw new ApiException(HttpStatus.CONFLICT, "A run for this project is already in progress.");
        }
        UUID usage;
        try {
            usage = subscriptions.reserve(ctx, Feature.MONITORING_RUN);
        } catch (ApiException e) {
            release(ctx.orgId(), projectId);
            throw e;
        }
        UUID runId = startRun(ctx.orgId(), projectId, Trigger.MANUAL, 1, null);
        pool.submit(() -> execute(ctx.orgId(), p, runId, Trigger.MANUAL, 1, null, usage));
        return run(ctx.orgId(), runId).orElseThrow();
    }

    /** Claims due projects and queues them; called by the scheduler and the external cron endpoint. */
    public int dispatchDue(int max) {
        Instant now = clock.instant();
        record Due(UUID org, UUID id, Instant nextRunAt, int failures) {}
        List<Due> due = jdbc.sql("SELECT organization_id, id, next_run_at, consecutive_failures FROM monitoring_projects "
                        + "WHERE status = 'ACTIVE' AND next_run_at <= ? AND (lease_until IS NULL OR lease_until < ?) ORDER BY next_run_at LIMIT ?")
                .params(Db.ts(now), Db.ts(now), max)
                .query((rs, i) -> new Due(Db.uuid(rs, "organization_id"), Db.uuid(rs, "id"), Db.instant(rs, "next_run_at"),
                        rs.getInt("consecutive_failures")))
                .list();
        int started = 0;
        for (Due d : due) {
            if (!claim(d.org(), d.id(), true)) {
                continue; // another instance took it
            }
            Optional<MonitoringProject> p = projects.find(d.org(), d.id());
            if (p.isEmpty()) {
                continue;
            }
            Trigger trigger = d.failures() > 0 ? Trigger.RETRY : Trigger.SCHEDULED;
            int attempt = d.failures() + 1;
            UUID usage;
            OrgContext system = OrgContext.system(d.org());
            try {
                usage = subscriptions.reserve(system, Feature.MONITORING_RUN);
            } catch (ApiException e) {
                // No plan or quota used up: recorded as a skipped run (visible to the customer), checked again next interval.
                UUID runId = startRun(d.org(), d.id(), trigger, attempt, d.nextRunAt());
                finish(d.org(), d.id(), runId, "SKIPPED", List.of(), 0, 0, 0, e.getMessage(), p.get().checkIntervalHours(), false);
                continue;
            }
            UUID runId = startRun(d.org(), d.id(), trigger, attempt, d.nextRunAt());
            pool.submit(() -> execute(d.org(), p.get(), runId, trigger, attempt, d.nextRunAt(), usage));
            started++;
        }
        return started;
    }

    /** Runs left RUNNING by a restart are marked failed so they don't look in progress forever. */
    public int failInterruptedRuns() {
        Instant cutoff = clock.instant().minus(LEASE);
        return jdbc.sql("UPDATE monitoring_runs SET status = 'FAILED', finished_at = ?, error_summary = ? WHERE status = 'RUNNING' AND started_at < ?")
                .params(Db.ts(clock.instant()), "Interrupted (the service restarted during the run). It will run again on schedule.", Db.ts(cutoff))
                .update();
    }

    // ---------------------------------------------------------------- the run

    void execute(UUID orgId, MonitoringProject p, UUID runId, Trigger trigger, int attempt, Instant scheduledFor, UUID usageId) {
        List<MonitoringRun.SourceOutcome> outcomes = new ArrayList<>();
        int[] counts = new int[3]; // results, new mentions, new incidents
        List<String> problems = new ArrayList<>();
        try {
            UsageScope.measure(() -> {
                collect(orgId, p, runId, outcomes, counts, problems);
                return null;
            }, totals -> subscriptions.recordCost(usageId, totals));
        } catch (RuntimeException e) {
            log.warn("Monitoring run failed unexpectedly: {}", e.getClass().getSimpleName(), e);
            problems.add("Unexpected error during the run.");
        }
        long ran = outcomes.stream().filter(o -> !o.status().equals("UNAVAILABLE")).count();
        long failed = outcomes.stream().filter(o -> o.status().equals("FAILED")).count();
        String status = outcomes.isEmpty() || (ran > 0 && failed == ran) || (ran == 0) ? "FAILED"
                : failed > 0 || !problems.isEmpty() || outcomes.stream().anyMatch(o -> !o.status().equals("OK")) ? "PARTIAL"
                : "SUCCEEDED";
        if (scheduledFor != null) {
            Duration late = Duration.between(scheduledFor, clock.instant());
            if (late.compareTo(Duration.ofHours(p.checkIntervalHours())) > 0) {
                problems.add(0, "Started " + late.toHours() + " h late (the service was unavailable or asleep); earlier checks were missed, "
                        + "but this run looked back " + p.config().lookbackDays() + " day(s).");
            }
        }
        finish(orgId, p.id(), runId, status, outcomes, counts[0], counts[1], counts[2],
                problems.isEmpty() ? null : String.join(" ", problems), p.checkIntervalHours(), true);
        log.info("Monitoring run status={} trigger={} attempt={} results={} newMentions={} newIncidents={}",
                status, trigger, attempt, counts[0], counts[1], counts[2]);
    }

    private void collect(UUID orgId, MonitoringProject p, UUID runId, List<MonitoringRun.SourceOutcome> outcomes, int[] counts,
                         List<String> problems) {
        SourceConnector.Request request = new SourceConnector.Request(p.brand(), p.config(), QueryPlanner.queries(p.brand(), p.config(), maxQueries));
        List<MentionStore.Upserted> fresh = new ArrayList<>();
        Map<UUID, String> relevanceOf = new java.util.HashMap<>();
        Map<UUID, SourceConnector.Item> itemOf = new java.util.HashMap<>();
        for (SourceConnector c : connectors) {
            if (!c.enabledFor(p.config())) {
                continue;
            }
            if (!c.available()) {
                outcomes.add(new MonitoringRun.SourceOutcome(c.id(), "UNAVAILABLE", 0, 0, "Not configured in this deployment."));
                continue;
            }
            List<SourceConnector.Item> items;
            String status = "OK";
            String detail = null;
            try {
                items = c.fetch(request);
            } catch (SourceConnector.PartialResult e) {
                items = e.items();
                status = "PARTIAL";
                detail = Text.clean(e.getMessage(), 300);
            } catch (SourceConnector.ConnectorException e) {
                outcomes.add(new MonitoringRun.SourceOutcome(c.id(), "FAILED", 0, 0, Text.clean(e.getMessage(), 300)));
                continue;
            } catch (RuntimeException e) {
                log.warn("Connector {} failed: {}", c.id(), e.getClass().getSimpleName());
                outcomes.add(new MonitoringRun.SourceOutcome(c.id(), "FAILED", 0, 0, "Unexpected error."));
                continue;
            }
            if (items.size() > MAX_ITEMS_PER_CONNECTOR) {
                items = items.subList(0, MAX_ITEMS_PER_CONNECTOR);
            }
            int relevant = 0;
            for (SourceConnector.Item item : items) {
                counts[0]++;
                Relevance.Result r = Relevance.evaluate(p.brand(), p.config(), item.title(), item.snippet());
                if (r == null) {
                    continue;
                }
                relevant++;
                MentionStore.Upserted u = mentions.upsert(orgId, p.id(), runId, c.id(), item, r);
                if (u == null || !u.isNew()) {
                    continue;
                }
                counts[1]++;
                if (u.copyOf()) {
                    if (u.copyIncidentId() != null) {
                        incidents.linkMention(orgId, u.copyIncidentId(), new IncidentService.MentionRef(u.id(), item.url(), item.title(),
                                null, null, r.level().name()));
                    }
                    continue;
                }
                fresh.add(u);
                relevanceOf.put(u.id(), r.level().name());
                itemOf.put(u.id(), item);
            }
            outcomes.add(new MonitoringRun.SourceOutcome(c.id(), status, items.size(), relevant, detail));
        }

        // Claims: high-relevance mentions first, within the extractor's batch size.
        List<MentionStore.Upserted> candidates = fresh.stream()
                .filter(u -> !"LOW".equals(relevanceOf.get(u.id())))
                .sorted(Comparator.comparing(u -> "HIGH".equals(relevanceOf.get(u.id())) ? 0 : 1))
                .limit(MentionClaimExtractor.MAX_MENTIONS).toList();
        if (candidates.isEmpty()) {
            return;
        }
        Map<String, MentionClaimExtractor.Found> found;
        try {
            found = extractor.extract(p.brand(), candidates.stream().map(u -> new MentionClaimExtractor.Input(u.id().toString(),
                    itemOf.get(u.id()).title(), itemOf.get(u.id()).snippet())).toList());
        } catch (RuntimeException e) {
            problems.add("Claim extraction was unavailable; new mentions were saved without claims.");
            log.warn("Claim extraction failed: {}", e.getClass().getSimpleName());
            return;
        }
        String threshold = p.config().alertThreshold();
        for (MentionStore.Upserted u : candidates) {
            MentionClaimExtractor.Found f = found.get(u.id().toString());
            if (f == null) {
                continue;
            }
            mentions.setClaim(orgId, u.id(), f);
            if (!meets(f.severity(), threshold)) {
                continue;
            }
            SourceConnector.Item item = itemOf.get(u.id());
            IncidentService.MentionRef ref = new IncidentService.MentionRef(u.id(), item.url(), item.title(), f.claim(), f.severity(),
                    relevanceOf.get(u.id()));
            Optional<UUID> open = incidents.findSimilarOpen(orgId, p.id(), f.claim());
            if (open.isPresent()) {
                incidents.linkMention(orgId, open.get(), ref);
            } else {
                incidents.openFromMention(orgId, p.id(), ref);
                counts[2]++;
            }
        }
    }

    static boolean meets(String severity, String threshold) {
        return switch (threshold == null ? "HIGH" : threshold) {
            case "NONE" -> false;
            case "MEDIUM" -> "HIGH".equals(severity) || "MEDIUM".equals(severity);
            default -> "HIGH".equals(severity);
        };
    }

    // ---------------------------------------------------------------- persistence

    private boolean claim(UUID orgId, UUID projectId, boolean scheduled) {
        Instant now = clock.instant();
        String due = scheduled ? " AND status = 'ACTIVE' AND next_run_at <= ?" : "";
        List<Object> params = new ArrayList<>(List.of(Db.ts(now.plus(LEASE)), orgId, projectId, Db.ts(now)));
        if (scheduled) {
            params.add(Db.ts(now));
        }
        return jdbc.sql("UPDATE monitoring_projects SET lease_until = ? WHERE organization_id = ? AND id = ? "
                        + "AND (lease_until IS NULL OR lease_until < ?)" + due)
                .params(params).update() == 1;
    }

    private void release(UUID orgId, UUID projectId) {
        jdbc.sql("UPDATE monitoring_projects SET lease_until = NULL WHERE organization_id = ? AND id = ?").params(orgId, projectId).update();
    }

    private UUID startRun(UUID orgId, UUID projectId, Trigger trigger, int attempt, Instant scheduledFor) {
        UUID id = UUID.randomUUID();
        jdbc.sql("INSERT INTO monitoring_runs (id, organization_id, project_id, trigger_kind, status, attempt, scheduled_for, started_at) "
                        + "VALUES (?, ?, ?, ?, 'RUNNING', ?, ?, ?)")
                .params(id, orgId, projectId, trigger.name(), attempt, Db.ts(scheduledFor), Db.ts(clock.instant())).update();
        return id;
    }

    private void finish(UUID orgId, UUID projectId, UUID runId, String status, List<MonitoringRun.SourceOutcome> outcomes, int results,
                        int newMentions, int newIncidents, String error, int intervalHours, boolean countsAsAttempt) {
        Instant now = clock.instant();
        jdbc.sql("UPDATE monitoring_runs SET status = ?, finished_at = ?, results_found = ?, new_mentions = ?, new_incidents = ?, "
                        + "sources_json = ?, error_summary = ? WHERE organization_id = ? AND id = ?")
                .params(status, Db.ts(now), results, newMentions, newIncidents, json.writeValueAsString(outcomes), Text.clean(error, 500), orgId, runId)
                .update();
        Instant regular = now.plus(Duration.ofHours(intervalHours));
        if (status.equals("FAILED") && countsAsAttempt) {
            int failures = jdbc.sql("SELECT consecutive_failures FROM monitoring_projects WHERE organization_id = ? AND id = ?")
                    .params(orgId, projectId).query(Integer.class).optional().orElse(0) + 1;
            Instant next = failures <= MAX_RETRIES ? now.plus(FIRST_RETRY.multipliedBy(1L << (failures - 1))) : regular;
            jdbc.sql("UPDATE monitoring_projects SET consecutive_failures = ?, last_run_at = ?, lease_until = NULL, "
                            + "next_run_at = CASE WHEN status = 'ACTIVE' THEN ? ELSE next_run_at END WHERE organization_id = ? AND id = ?")
                    .params(failures, Db.ts(now), Db.ts(next), orgId, projectId).update();
        } else if (status.equals("SKIPPED")) {
            jdbc.sql("UPDATE monitoring_projects SET lease_until = NULL, next_run_at = CASE WHEN status = 'ACTIVE' THEN ? ELSE next_run_at END "
                            + "WHERE organization_id = ? AND id = ?")
                    .params(Db.ts(regular), orgId, projectId).update();
        } else {
            jdbc.sql("UPDATE monitoring_projects SET consecutive_failures = 0, last_run_at = ?, "
                            + "last_success_at = CASE WHEN ? THEN ? ELSE last_success_at END, lease_until = NULL, "
                            + "next_run_at = CASE WHEN status = 'ACTIVE' THEN ? ELSE next_run_at END WHERE organization_id = ? AND id = ?")
                    .params(Db.ts(now), status.equals("SUCCEEDED"), Db.ts(now), Db.ts(regular), orgId, projectId).update();
        }
    }

    // ---------------------------------------------------------------- reads

    public List<MonitoringRun> runs(OrgContext ctx, UUID projectId, int limit) {
        String where = projectId == null ? "r.organization_id = ?" : "r.organization_id = ? AND r.project_id = ?";
        List<Object> params = new ArrayList<>(List.of(ctx.orgId()));
        if (projectId != null) {
            params.add(projectId);
        }
        params.add(Math.min(Math.max(limit, 1), 100));
        return jdbc.sql("SELECT r.*, p.name AS project_name FROM monitoring_runs r JOIN monitoring_projects p ON p.id = r.project_id WHERE "
                        + where + " ORDER BY r.started_at DESC LIMIT ?")
                .params(params).query((rs, i) -> map(rs)).list();
    }

    Optional<MonitoringRun> run(UUID orgId, UUID runId) {
        return jdbc.sql("SELECT r.*, p.name AS project_name FROM monitoring_runs r JOIN monitoring_projects p ON p.id = r.project_id "
                        + "WHERE r.organization_id = ? AND r.id = ?")
                .params(orgId, runId).query((rs, i) -> map(rs)).optional();
    }

    private MonitoringRun map(java.sql.ResultSet rs) throws java.sql.SQLException {
        String sources = rs.getString("sources_json");
        return new MonitoringRun(Db.uuid(rs, "id"), Db.uuid(rs, "project_id"), rs.getString("project_name"), rs.getString("trigger_kind"),
                rs.getString("status"), rs.getInt("attempt"), Db.instant(rs, "scheduled_for"), Db.instant(rs, "started_at"),
                Db.instant(rs, "finished_at"), rs.getInt("results_found"), rs.getInt("new_mentions"), rs.getInt("new_incidents"),
                sources == null ? List.of() : json.readValue(sources, new TypeReference<List<MonitoringRun.SourceOutcome>>() {}),
                rs.getString("error_summary"));
    }
}
