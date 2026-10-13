package com.ai.agent.verifact.intelligence.investigations;

import com.ai.agent.verifact.account.Role;
import com.ai.agent.verifact.ai.UsageScope;
import com.ai.agent.verifact.common.ApiException;
import com.ai.agent.verifact.intelligence.AuditLog;
import com.ai.agent.verifact.intelligence.Db;
import com.ai.agent.verifact.intelligence.OrgContext;
import com.ai.agent.verifact.intelligence.Text;
import com.ai.agent.verifact.intelligence.billing.Feature;
import com.ai.agent.verifact.intelligence.billing.SubscriptionService;
import com.ai.agent.verifact.intelligence.incidents.IncidentService;
import com.ai.agent.verifact.intelligence.workspace.WorkspaceService;
import com.ai.agent.verifact.news.NewsCheck;
import com.ai.agent.verifact.news.NewsCheckService;
import com.ai.agent.verifact.verification.VerificationProgress;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Evidence investigations. A run uses the evidence pipeline shared with NewsFact (claims grounded in the input,
 * backend web search, verdicts that stand only on sources' verbatim words) and is stored as a new version, so
 * earlier assessments are kept and the changes are recorded. Runs are metered and execute in the background; the
 * client polls. Evidence is never shared between organisations: each run searches afresh.
 */
@Service
public class InvestigationService {

    private static final Logger log = LoggerFactory.getLogger(InvestigationService.class);

    static final Duration STALE = Duration.ofMinutes(15);

    public record StartInput(String title, String input, String incidentId) {}

    private final JdbcClient jdbc;
    private final JsonMapper json;
    private final TransactionTemplate tx;
    private final NewsCheckService checks;
    private final SubscriptionService subscriptions;
    private final IncidentService incidents;
    private final AuditLog audit;
    private final Clock clock;
    private final int maxInputChars;
    private final ExecutorService pool;

    public InvestigationService(JdbcClient jdbc, JsonMapper json, TransactionTemplate tx, NewsCheckService checks,
                                SubscriptionService subscriptions, IncidentService incidents, AuditLog audit, Clock clock,
                                @Value("${app.input.max-chars:10000}") int maxInputChars,
                                @Value("${app.investigations.concurrency:2}") int concurrency) {
        this.jdbc = jdbc;
        this.json = json;
        this.tx = tx;
        this.checks = checks;
        this.subscriptions = subscriptions;
        this.incidents = incidents;
        this.audit = audit;
        this.clock = clock;
        this.maxInputChars = maxInputChars;
        this.pool = Executors.newFixedThreadPool(Math.max(1, concurrency), r -> {
            Thread t = new Thread(r, "investigation");
            t.setDaemon(true);
            return t;
        });
    }

    public Investigation.Detail start(OrgContext ctx, StartInput in) {
        if (in == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Missing investigation details.");
        }
        String input = Text.cleanMultiline(in.input(), Integer.MAX_VALUE);
        if (input == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Enter the claim, text or link to investigate.");
        }
        if (input.length() > maxInputChars) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "That's too long to investigate: at most " + maxInputChars + " characters.");
        }
        UUID incidentId = in.incidentId() == null || in.incidentId().isBlank() ? null : incident(ctx, in.incidentId());
        String title = Text.clean(in.title(), 200);
        if (title == null) {
            title = Text.clean(input, 120);
        }
        UUID usage = subscriptions.reserve(ctx, Feature.INVESTIGATION);
        UUID id = UUID.randomUUID();
        Instant now = clock.instant();
        jdbc.sql("INSERT INTO investigations (id, organization_id, incident_id, title, input_text, status, created_by, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, 'RUNNING', ?, ?, ?)")
                .params(id, ctx.orgId(), incidentId, title, input, ctx.userId(), Db.ts(now), Db.ts(now)).update();
        if (incidentId != null) {
            incidents.investigationStarted(ctx.orgId(), incidentId, ctx.userId());
        }
        pool.submit(() -> execute(ctx.orgId(), id, input, usage));
        return get(ctx, id);
    }

    /** Runs the investigation again with fresh searches: a new version, with what changed. */
    public Investigation.Detail rerun(OrgContext ctx, UUID id) {
        Investigation.Detail d = get(ctx, id);
        if (d.summary().status().equals("RUNNING")) {
            throw new ApiException(HttpStatus.CONFLICT, "This investigation is already running.");
        }
        UUID usage = subscriptions.reserve(ctx, Feature.INVESTIGATION);
        int updated = jdbc.sql("UPDATE investigations SET status = 'RUNNING', error_message = NULL, updated_at = ? "
                        + "WHERE organization_id = ? AND id = ? AND status <> 'RUNNING'")
                .params(Db.ts(clock.instant()), ctx.orgId(), id).update();
        if (updated == 0) {
            throw new ApiException(HttpStatus.CONFLICT, "This investigation is already running.");
        }
        pool.submit(() -> execute(ctx.orgId(), id, d.input(), usage));
        return get(ctx, id);
    }

    void execute(UUID orgId, UUID id, String input, UUID usageId) {
        try {
            NewsCheck check = UsageScope.measure(() -> checks.check(input, VerificationProgress.NONE, false),
                    totals -> subscriptions.recordCost(usageId, totals));
            complete(orgId, id, check);
        } catch (ApiException e) {
            fail(orgId, id, e.getMessage().replace("NewsFact", "VeriFact"));
        } catch (RuntimeException e) {
            log.warn("Investigation failed: {}", e.getClass().getSimpleName(), e);
            fail(orgId, id, "The investigation couldn't be completed. Please try again.");
        }
    }

    void complete(UUID orgId, UUID id, NewsCheck check) {
        Instant now = clock.instant();
        record Done(int version, Investigation.Result result, UUID incidentId) {}
        Done done = tx.execute(s -> {
            int version = jdbc.sql("SELECT current_version FROM investigations WHERE organization_id = ? AND id = ?").params(orgId, id)
                    .query(Integer.class).optional().orElse(-1) + 1;
            if (version == 0) {
                return null; // deleted meanwhile
            }
            Investigation.Result result = InvestigationMapper.map(check, version);
            Investigation.Result previous = version > 1 ? version(orgId, id, version - 1).orElse(null) : null;
            Investigation.Changes changes = InvestigationMapper.diff(previous, result);
            jdbc.sql("INSERT INTO investigation_versions (investigation_id, version, organization_id, created_at, result_json, changes_json) "
                            + "VALUES (?, ?, ?, ?, ?, ?)")
                    .params(id, version, orgId, Db.ts(now), json.writeValueAsString(result), changes == null ? null : json.writeValueAsString(changes))
                    .update();
            for (Investigation.Source src : result.sources()) {
                Investigation.Excerpt ex = result.claims().stream()
                        .flatMap(c -> java.util.stream.Stream.of(c.supporting(), c.contradicting(), c.quoteSource()))
                        .filter(x -> x != null && x.sourceId().equals(src.id())).findFirst().orElse(null);
                jdbc.sql("INSERT INTO evidence_records (id, organization_id, investigation_id, version, source_ref, url, domain, title, snippet, "
                                + "published_date, retrieved_at, source_type, direction, claim_refs, excerpt) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")
                        .params(UUID.randomUUID(), orgId, id, version, src.id(), Text.clean(src.url(), 2048), src.domain(), Text.clean(src.title(), 400),
                                Text.clean(src.snippet(), 1200), Text.clean(src.publishedDate(), 40), Db.ts(src.retrievedAt() == null ? now : src.retrievedAt()),
                                src.sourceType(), src.direction(), Text.clean(String.join(",", src.claimIds()), 200), ex == null ? null : Text.clean(ex.text(), 1200))
                        .update();
            }
            jdbc.sql("UPDATE investigations SET status = 'COMPLETED', current_version = ?, error_message = NULL, updated_at = ? "
                            + "WHERE organization_id = ? AND id = ?")
                    .params(version, Db.ts(now), orgId, id).update();
            UUID incident = jdbc.sql("SELECT incident_id FROM investigations WHERE organization_id = ? AND id = ?").params(orgId, id)
                    .query(UUID.class).optional().orElse(null);
            return new Done(version, result, incident);
        });
        if (done != null && done.incidentId() != null) {
            String headline = InvestigationMapper.headline(done.result());
            incidents.investigationCompleted(orgId, done.incidentId(), id, InvestigationMapper.evidenceState(done.result()),
                    "Investigation v" + done.version() + " completed" + (headline == null ? "." : ": main claim " + label(headline) + "."));
        }
    }

    private void fail(UUID orgId, UUID id, String message) {
        jdbc.sql("UPDATE investigations SET status = 'FAILED', error_message = ?, updated_at = ? WHERE organization_id = ? AND id = ?")
                .params(Text.clean(message, 500), Db.ts(clock.instant()), orgId, id).update();
    }

    /** Runs left RUNNING by a restart are marked failed so they don't look in progress forever. */
    @org.springframework.scheduling.annotation.Scheduled(fixedDelayString = "${app.investigations.sweep-millis:300000}",
            initialDelayString = "${app.investigations.sweep-millis:300000}")
    public void sweep() {
        try {
            failInterrupted();
        } catch (RuntimeException e) {
            log.warn("Investigation sweep failed: {}", e.getClass().getSimpleName());
        }
    }

    int failInterrupted() {
        return jdbc.sql("UPDATE investigations SET status = 'FAILED', error_message = ? WHERE status = 'RUNNING' AND updated_at < ?")
                .params("Interrupted (the service restarted). Run it again.", Db.ts(clock.instant().minus(STALE))).update();
    }

    // ---------------------------------------------------------------- reads and edits

    public Investigation.Page list(OrgContext ctx, String incidentId, Integer page, Integer size) {
        String where = " WHERE v.organization_id = ?" + (incidentId == null || incidentId.isBlank() ? "" : " AND v.incident_id = ?");
        List<Object> params = new ArrayList<>(List.of(ctx.orgId()));
        if (incidentId != null && !incidentId.isBlank()) {
            params.add(com.ai.agent.verifact.intelligence.IntelligenceAccess.uuid(incidentId));
        }
        int sz = Math.min(Math.max(size == null ? 25 : size, 1), 100);
        int pg = Math.max(page == null ? 0 : page, 0);
        long total = jdbc.sql("SELECT COUNT(*) FROM investigations v" + where).params(params).query(Long.class).single();
        List<Object> p2 = new ArrayList<>(params);
        p2.add(sz);
        p2.add(pg * sz);
        List<Investigation.Summary> items = jdbc.sql(SUMMARY + where + " ORDER BY v.updated_at DESC LIMIT ? OFFSET ?").params(p2)
                .query((rs, i) -> summary(rs, ctx.orgId())).list();
        return new Investigation.Page(items, pg, sz, total);
    }

    public Investigation.Detail get(OrgContext ctx, UUID id) {
        record Row(Investigation.Summary s, String input, String error, String notes) {}
        Row row = jdbc.sql(SUMMARY + " WHERE v.organization_id = ? AND v.id = ?").params(ctx.orgId(), id)
                .query((rs, i) -> new Row(summary(rs, ctx.orgId()), rs.getString("input_text"), rs.getString("error_message"), rs.getString("analyst_notes")))
                .optional().orElseThrow(InvestigationService::notFound);
        int v = row.s().version();
        Investigation.Result result = v > 0 ? version(ctx.orgId(), id, v).orElse(null) : null;
        Investigation.Changes changes = v > 1 ? changes(ctx.orgId(), id, v) : null;
        return new Investigation.Detail(row.s(), row.input(), row.error(), row.notes(), result, changes, versions(ctx.orgId(), id));
    }

    public Investigation.Result getVersion(OrgContext ctx, UUID id, int version) {
        get(ctx, id);
        return version(ctx.orgId(), id, version)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "That version doesn't exist."));
    }

    public Investigation.Detail updateNotes(OrgContext ctx, UUID id, String notes, boolean markReviewed) {
        get(ctx, id);
        Instant now = clock.instant();
        if (notes != null) {
            jdbc.sql("UPDATE investigations SET analyst_notes = ? WHERE organization_id = ? AND id = ?")
                    .params(Text.cleanMultiline(notes, 10_000), ctx.orgId(), id).update();
        }
        if (markReviewed) {
            jdbc.sql("UPDATE investigations SET last_reviewed_at = ? WHERE organization_id = ? AND id = ?").params(Db.ts(now), ctx.orgId(), id).update();
            audit.record(ctx, "investigation.reviewed", "investigation", id, null);
        }
        return get(ctx, id);
    }

    public void delete(OrgContext ctx, UUID id) {
        WorkspaceService.requireRole(ctx, Role.ADMIN);
        Investigation.Detail d = get(ctx, id);
        jdbc.sql("DELETE FROM investigations WHERE organization_id = ? AND id = ?").params(ctx.orgId(), id).update();
        jdbc.sql("UPDATE incidents SET investigation_id = NULL WHERE organization_id = ? AND investigation_id = ?").params(ctx.orgId(), id).update();
        audit.record(ctx, "investigation.deleted", "investigation", id, d.summary().title());
    }

    /** The latest completed result, for Response Studio and reports. */
    public Optional<Investigation.Result> latest(OrgContext ctx, UUID id) {
        Investigation.Detail d = get(ctx, id);
        return Optional.ofNullable(d.result());
    }

    Optional<Investigation.Result> version(UUID orgId, UUID id, int version) {
        return jdbc.sql("SELECT result_json FROM investigation_versions WHERE organization_id = ? AND investigation_id = ? AND version = ?")
                .params(orgId, id, version).query(String.class).optional().map(j -> json.readValue(j, Investigation.Result.class));
    }

    private Investigation.Changes changes(UUID orgId, UUID id, int version) {
        return jdbc.sql("SELECT changes_json FROM investigation_versions WHERE organization_id = ? AND investigation_id = ? AND version = ?")
                .params(orgId, id, version).query(String.class).optional().filter(j -> j != null)
                .map(j -> json.readValue(j, Investigation.Changes.class)).orElse(null);
    }

    private List<Investigation.VersionSummary> versions(UUID orgId, UUID id) {
        return jdbc.sql("SELECT version, created_at, result_json, changes_json FROM investigation_versions "
                        + "WHERE organization_id = ? AND investigation_id = ? ORDER BY version DESC")
                .params(orgId, id)
                .query((rs, i) -> {
                    Investigation.Result r = json.readValue(rs.getString("result_json"), Investigation.Result.class);
                    String c = rs.getString("changes_json");
                    return new Investigation.VersionSummary(rs.getInt("version"), Db.instant(rs, "created_at"), r.claims().size(), r.sources().size(),
                            c == null ? null : json.readValue(c, Investigation.Changes.class));
                }).list();
    }

    private static final String SUMMARY = "SELECT v.*, i.title AS incident_title FROM investigations v "
            + "LEFT JOIN incidents i ON i.id = v.incident_id AND i.organization_id = v.organization_id";

    private Investigation.Summary summary(ResultSet rs, UUID orgId) throws SQLException {
        int version = rs.getInt("current_version");
        String headline = null;
        if (version > 0) {
            headline = version(orgId, Db.uuid(rs, "id"), version).map(InvestigationMapper::headline).orElse(null);
        }
        return new Investigation.Summary(Db.uuid(rs, "id"), rs.getString("title"), rs.getString("status"), version, Db.uuid(rs, "incident_id"),
                rs.getString("incident_title"), headline, Db.instant(rs, "created_at"), Db.instant(rs, "updated_at"), Db.instant(rs, "last_reviewed_at"));
    }

    private UUID incident(OrgContext ctx, String incidentId) {
        UUID id = com.ai.agent.verifact.intelligence.IntelligenceAccess.uuid(incidentId);
        if (!incidents.exists(ctx, id)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "That incident doesn't exist.");
        }
        return id;
    }

    static String label(String assessment) {
        return switch (assessment) {
            case "SUPPORTED" -> "supported by available evidence";
            case "CONTRADICTED" -> "contradicted by available evidence";
            case "MIXED" -> "mixed evidence";
            case "INSUFFICIENT_EVIDENCE" -> "insufficient evidence";
            case "MISSING_CONTEXT" -> "outdated or missing context";
            default -> "under investigation";
        };
    }

    static ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "That investigation doesn't exist (it may have been deleted).");
    }
}
