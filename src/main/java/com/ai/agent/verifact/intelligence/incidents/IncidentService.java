package com.ai.agent.verifact.intelligence.incidents;

import com.ai.agent.verifact.account.Role;
import com.ai.agent.verifact.common.ApiException;
import com.ai.agent.verifact.evidence.SourceType;
import com.ai.agent.verifact.evidence.Urls;
import com.ai.agent.verifact.intelligence.AuditLog;
import com.ai.agent.verifact.intelligence.Db;
import com.ai.agent.verifact.intelligence.OrgContext;
import com.ai.agent.verifact.intelligence.Text;
import com.ai.agent.verifact.intelligence.workspace.WorkspaceService;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Incidents: potentially damaging claims handled as cases. Monitoring opens them (or links repeated reports to an
 * open one); people can open them by hand, escalate a mention, assign, change status and priority, and add notes.
 * Every change is an incident event (the activity history).
 */
@Service
public class IncidentService {

    static final double LINK_SIMILARITY = 0.5;
    static final double RELATED_SIMILARITY = 0.25;
    static final int MAX_PAGE_SIZE = 100;
    static final Set<Incident.Status> OPEN = Set.of(Incident.Status.NEW, Incident.Status.NEEDS_REVIEW, Incident.Status.INVESTIGATING,
            Incident.Status.AWAITING_EVIDENCE, Incident.Status.RESPONSE_DRAFTED, Incident.Status.MONITORING);

    /** A mention that opens or joins an incident. */
    public record MentionRef(UUID mentionId, String url, String title, String claim, String severity, String relevance) {}

    public record ManualInput(String title, String claim, String discoveryUrl, String projectId, String severity) {}

    /** Null fields are left unchanged; {@code ownerId} "" unassigns; {@code priority} "AUTO" returns to the computed priority. */
    public record Update(String title, String status, String ownerId, String priority, String nextSteps) {}

    public record Filter(String status, String priority, String projectId, String ownerId, String q, String sort, Integer page, Integer size) {}

    private final JdbcClient jdbc;
    private final JsonMapper json;
    private final TransactionTemplate tx;
    private final WorkspaceService workspaces;
    private final AuditLog audit;
    private final Clock clock;

    public IncidentService(JdbcClient jdbc, JsonMapper json, TransactionTemplate tx, WorkspaceService workspaces, AuditLog audit, Clock clock) {
        this.jdbc = jdbc;
        this.json = json;
        this.tx = tx;
        this.workspaces = workspaces;
        this.audit = audit;
        this.clock = clock;
    }

    // ---------------------------------------------------------------- from monitoring

    /** An open incident in the project whose claim is about the same allegation, if any. */
    public Optional<UUID> findSimilarOpen(UUID orgId, UUID projectId, String claim) {
        record Row(UUID id, String claim) {}
        return jdbc.sql("SELECT id, claim FROM incidents WHERE organization_id = ? AND project_id = ? AND status <> 'RESOLVED' "
                        + "ORDER BY last_seen_at DESC LIMIT 200")
                .params(orgId, projectId).query((rs, i) -> new Row(Db.uuid(rs, "id"), rs.getString("claim"))).list().stream()
                .filter(r -> Similarity.jaccard(r.claim(), claim) >= LINK_SIMILARITY).map(Row::id).findFirst();
    }

    /** Opens an incident for a mention's claim (system action during a monitoring run). */
    public UUID openFromMention(UUID orgId, UUID projectId, MentionRef m) {
        Instant now = clock.instant();
        UUID id = UUID.randomUUID();
        RiskModel.Inputs inputs = new RiskModel.Inputs(m.severity(), sourceType(m.url()), 1, now, m.relevance(),
                RiskModel.EvidenceState.NOT_INVESTIGATED);
        RiskModel.Assessment risk = RiskModel.assess(inputs, now);
        String title = Text.clean(m.claim(), 200);
        tx.executeWithoutResult(s -> {
            insert(id, orgId, projectId, title, m.claim(), m.url(), risk, null, now);
            jdbc.sql("UPDATE mentions SET incident_id = ?, status = 'ESCALATED' WHERE organization_id = ? AND id = ?")
                    .params(id, orgId, m.mentionId()).update();
            event(orgId, id, null, "OPENED", "Opened by monitoring from " + host(m.url()) + ".");
        });
        return id;
    }

    /** Links another report of the allegation to an open incident and refreshes its risk. */
    public void linkMention(UUID orgId, UUID incidentId, MentionRef m) {
        Instant now = clock.instant();
        tx.executeWithoutResult(s -> {
            jdbc.sql("UPDATE mentions SET incident_id = ?, status = 'ESCALATED' WHERE organization_id = ? AND id = ?")
                    .params(incidentId, orgId, m.mentionId()).update();
            jdbc.sql("UPDATE incidents SET last_seen_at = ?, updated_at = ? WHERE organization_id = ? AND id = ?")
                    .params(Db.ts(now), Db.ts(now), orgId, incidentId).update();
            event(orgId, incidentId, null, "MENTION_LINKED", "Seen again on " + host(m.url()) + ".");
            if (stronger(m.severity(), inputs(orgId, incidentId).severity()) || stronger(m.relevance(), inputs(orgId, incidentId).relevance())) {
                RiskModel.Inputs in = inputs(orgId, incidentId);
                saveInputs(orgId, incidentId, new RiskModel.Inputs(stronger(m.severity(), in.severity()) ? m.severity() : in.severity(),
                        in.sourceType(), in.distinctSites(), in.lastSeenAt(), stronger(m.relevance(), in.relevance()) ? m.relevance() : in.relevance(),
                        in.evidence()));
            }
            recompute(orgId, incidentId);
        });
    }

    // ---------------------------------------------------------------- people

    public UUID createManual(OrgContext ctx, ManualInput in) {
        if (in == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Missing incident details.");
        }
        String claim = Text.required(in.claim(), 1000, "Describe the claim or allegation.");
        String title = Text.clean(in.title(), 200);
        String url = Text.clean(in.discoveryUrl(), 2000);
        if (url != null && Urls.normalizeUrl(url) == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "The discovery link must be a web address (http or https).");
        }
        UUID projectId = in.projectId() == null || in.projectId().isBlank() ? null : projectInOrg(ctx, in.projectId());
        String severity = in.severity() == null ? null : in.severity().trim().toUpperCase(Locale.ROOT);
        if (severity != null && !Set.of("HIGH", "MEDIUM", "LOW").contains(severity)) {
            severity = null;
        }
        Instant now = clock.instant();
        UUID id = UUID.randomUUID();
        RiskModel.Assessment risk = RiskModel.assess(new RiskModel.Inputs(severity, url == null ? null : sourceType(url), url == null ? 0 : 1,
                now, null, RiskModel.EvidenceState.NOT_INVESTIGATED), now);
        String t = title == null ? Text.clean(claim, 200) : title;
        tx.executeWithoutResult(s -> {
            insert(id, ctx.orgId(), projectId, t, claim, url, risk, ctx.userId(), now);
            event(ctx.orgId(), id, ctx.userId(), "OPENED", "Opened by a team member.");
        });
        return id;
    }

    /** A person turns a mention into an incident (or links it to the open incident about the same claim). */
    public UUID escalateMention(OrgContext ctx, UUID mentionId) {
        record M(UUID project, String url, String title, String snippet, String claim, String severity, String relevance, UUID incident) {}
        M m = jdbc.sql("SELECT project_id, url, title, snippet, claim, claim_severity, relevance, incident_id FROM mentions "
                        + "WHERE organization_id = ? AND id = ?")
                .params(ctx.orgId(), mentionId)
                .query((rs, i) -> new M(Db.uuid(rs, "project_id"), rs.getString("url"), rs.getString("title"), rs.getString("snippet"),
                        rs.getString("claim"), rs.getString("claim_severity"), rs.getString("relevance"), Db.uuid(rs, "incident_id")))
                .optional().orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "That mention doesn't exist."));
        if (m.incident() != null) {
            return m.incident();
        }
        // Without an extracted claim, the mention's own headline is the allegation to look into.
        String claim = m.claim() != null ? m.claim() : Text.clean(m.title() != null && !m.title().isBlank() ? m.title() : m.snippet(), 1000);
        if (claim == null) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "That mention has no text to open an incident from.");
        }
        MentionRef ref = new MentionRef(mentionId, m.url(), m.title(), claim, m.severity(), m.relevance());
        Optional<UUID> existing = findSimilarOpen(ctx.orgId(), m.project(), claim);
        if (existing.isPresent()) {
            linkMention(ctx.orgId(), existing.get(), ref);
            return existing.get();
        }
        UUID id = openFromMention(ctx.orgId(), m.project(), ref);
        jdbc.sql("UPDATE incidents SET created_by = ? WHERE organization_id = ? AND id = ?").params(ctx.userId(), ctx.orgId(), id).update();
        jdbc.sql("UPDATE incident_events SET detail = ?, actor_id = ? WHERE organization_id = ? AND incident_id = ? AND kind = 'OPENED'")
                .params("Escalated from a mention on " + host(m.url()) + " by a team member.", ctx.userId(), ctx.orgId(), id).update();
        return id;
    }

    public Incident.Page list(OrgContext ctx, Filter f) {
        Filter filter = f == null ? new Filter(null, null, null, null, null, null, null, null) : f;
        StringBuilder where = new StringBuilder(" WHERE i.organization_id = ?");
        List<Object> params = new ArrayList<>(List.of(ctx.orgId()));
        if (filter.status() != null && !filter.status().isBlank()) {
            if (filter.status().equalsIgnoreCase("OPEN")) {
                where.append(" AND i.status <> 'RESOLVED'");
            } else {
                where.append(" AND i.status = ?");
                params.add(status(filter.status()).name());
            }
        }
        if (filter.priority() != null && !filter.priority().isBlank()) {
            where.append(" AND i.priority = ?");
            params.add(priority(filter.priority()).name());
        }
        if (filter.projectId() != null && !filter.projectId().isBlank()) {
            where.append(" AND i.project_id = ?");
            params.add(uuidOr404(filter.projectId()));
        }
        if (filter.ownerId() != null && !filter.ownerId().isBlank()) {
            if (filter.ownerId().equals("none")) {
                where.append(" AND i.owner_id IS NULL");
            } else {
                where.append(" AND i.owner_id = ?");
                params.add(uuidOr404(filter.ownerId()));
            }
        }
        String q = Text.clean(filter.q(), 100);
        if (q != null) {
            where.append(" AND (LOWER(i.title) LIKE ? ESCAPE '\\' OR LOWER(i.claim) LIKE ? ESCAPE '\\')");
            String like = "%" + q.toLowerCase(Locale.ROOT).replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
            params.add(like);
            params.add(like);
        }
        String order = switch (filter.sort() == null ? "" : filter.sort()) {
            case "risk" -> " ORDER BY i.risk_score DESC, i.updated_at DESC";
            case "priority" -> " ORDER BY CASE i.priority WHEN 'CRITICAL' THEN 0 WHEN 'HIGH' THEN 1 WHEN 'MEDIUM' THEN 2 ELSE 3 END, i.updated_at DESC";
            case "firstSeen" -> " ORDER BY i.first_seen_at DESC";
            default -> " ORDER BY i.updated_at DESC";
        };
        int size = Math.min(Math.max(filter.size() == null ? 25 : filter.size(), 1), MAX_PAGE_SIZE);
        int page = Math.max(filter.page() == null ? 0 : filter.page(), 0);
        long total = jdbc.sql("SELECT COUNT(*) FROM incidents i" + where).params(params).query(Long.class).single();
        List<Object> pageParams = new ArrayList<>(params);
        pageParams.add(size);
        pageParams.add(page * size);
        List<Incident.Summary> items = jdbc.sql(SUMMARY_SELECT + where + order + " LIMIT ? OFFSET ?").params(pageParams)
                .query((rs, i) -> summary(rs)).list();
        return new Incident.Page(items, page, size, total);
    }

    public Incident.Detail get(OrgContext ctx, UUID id) {
        Incident.Summary s = jdbc.sql(SUMMARY_SELECT + " WHERE i.organization_id = ? AND i.id = ?").params(ctx.orgId(), id)
                .query((rs, i) -> summary(rs)).optional().orElseThrow(IncidentService::notFound);
        record Extra(String url, String riskJson, boolean override, String nextSteps) {}
        Extra x = jdbc.sql("SELECT discovery_url, risk_json, priority_override, next_steps FROM incidents WHERE organization_id = ? AND id = ?")
                .params(ctx.orgId(), id)
                .query((rs, i) -> new Extra(rs.getString(1), rs.getString(2), rs.getBoolean(3), rs.getString(4))).single();
        RiskModel.Assessment risk = json.readValue(x.riskJson(), RiskModel.Assessment.class);
        List<Incident.Event> events = jdbc.sql("SELECT e.id, e.kind, e.detail, e.actor_id, u.email, e.created_at FROM incident_events e "
                        + "LEFT JOIN app_users u ON u.id = e.actor_id WHERE e.organization_id = ? AND e.incident_id = ? ORDER BY e.created_at, e.id")
                .params(ctx.orgId(), id)
                .query((rs, i) -> new Incident.Event(Db.uuid(rs, "id"), rs.getString("kind"), rs.getString("detail"), Db.uuid(rs, "actor_id"),
                        rs.getString("email"), Db.instant(rs, "created_at"))).list();
        List<Incident.LinkedMention> mentions = jdbc.sql("SELECT id, url, domain, title, snippet, published_date, connector, relevance, "
                        + "claim_quote, first_seen_at FROM mentions WHERE organization_id = ? AND incident_id = ? ORDER BY first_seen_at")
                .params(ctx.orgId(), id)
                .query((rs, i) -> new Incident.LinkedMention(Db.uuid(rs, "id"), rs.getString("url"), rs.getString("domain"), rs.getString("title"),
                        rs.getString("snippet"), rs.getString("published_date"), rs.getString("connector"), rs.getString("relevance"),
                        rs.getString("claim_quote"), Db.instant(rs, "first_seen_at"))).list();
        List<Incident.LinkedInvestigation> investigations = jdbc.sql("SELECT id, title, status, current_version, updated_at FROM investigations "
                        + "WHERE organization_id = ? AND incident_id = ? ORDER BY created_at DESC")
                .params(ctx.orgId(), id)
                .query((rs, i) -> new Incident.LinkedInvestigation(Db.uuid(rs, "id"), rs.getString("title"), rs.getString("status"),
                        rs.getInt("current_version"), Db.instant(rs, "updated_at"))).list();
        List<Incident.Summary> related = jdbc.sql(SUMMARY_SELECT + " WHERE i.organization_id = ? AND i.id <> ? ORDER BY i.last_seen_at DESC LIMIT 300")
                .params(ctx.orgId(), id).query((rs, i) -> summary(rs)).list().stream()
                .filter(o -> Similarity.jaccard(o.claim(), s.claim()) >= RELATED_SIMILARITY).limit(5).toList();
        return new Incident.Detail(s, x.url(), risk, x.override(), x.nextSteps(), suggestions(s, risk), events, mentions, related, investigations);
    }

    public Incident.Detail update(OrgContext ctx, UUID id, Update u) {
        if (u == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Missing changes.");
        }
        Incident.Detail before = get(ctx, id);
        Instant now = clock.instant();
        tx.executeWithoutResult(s -> {
            if (u.title() != null) {
                String title = Text.required(u.title(), 200, "Enter a title.");
                if (!title.equals(before.summary().title())) {
                    jdbc.sql("UPDATE incidents SET title = ? WHERE organization_id = ? AND id = ?").params(title, ctx.orgId(), id).update();
                    event(ctx.orgId(), id, ctx.userId(), "RENAMED", title);
                }
            }
            if (u.status() != null) {
                Incident.Status next = status(u.status());
                if (next != before.summary().status()) {
                    jdbc.sql("UPDATE incidents SET status = ?, resolved_at = ? WHERE organization_id = ? AND id = ?")
                            .params(next.name(), next == Incident.Status.RESOLVED ? Db.ts(now) : null, ctx.orgId(), id).update();
                    event(ctx.orgId(), id, ctx.userId(), "STATUS", label(before.summary().status()) + " → " + label(next));
                }
            }
            if (u.ownerId() != null) {
                UUID owner = u.ownerId().isBlank() ? null : uuidOr404(u.ownerId());
                if (owner != null && !workspaces.isMember(ctx, owner)) {
                    throw new ApiException(HttpStatus.BAD_REQUEST, "The owner must be a member of this workspace.");
                }
                if (!java.util.Objects.equals(owner, before.summary().ownerId())) {
                    jdbc.sql("UPDATE incidents SET owner_id = ? WHERE organization_id = ? AND id = ?").params(owner, ctx.orgId(), id).update();
                    event(ctx.orgId(), id, ctx.userId(), "ASSIGNED", owner == null ? "Unassigned." : "Assigned to " + email(owner) + ".");
                }
            }
            if (u.priority() != null) {
                if (u.priority().equalsIgnoreCase("AUTO")) {
                    jdbc.sql("UPDATE incidents SET priority_override = FALSE WHERE organization_id = ? AND id = ?").params(ctx.orgId(), id).update();
                    recompute(ctx.orgId(), id);
                    event(ctx.orgId(), id, ctx.userId(), "PRIORITY", "Priority returned to the computed score.");
                } else {
                    WorkspaceService.requireRole(ctx, Role.ADMIN);
                    RiskModel.Priority p = priority(u.priority());
                    jdbc.sql("UPDATE incidents SET priority = ?, priority_override = TRUE WHERE organization_id = ? AND id = ?")
                            .params(p.name(), ctx.orgId(), id).update();
                    event(ctx.orgId(), id, ctx.userId(), "PRIORITY", "Priority set to " + p.name().toLowerCase(Locale.ROOT) + " by a reviewer (overrides the computed score).");
                }
            }
            if (u.nextSteps() != null) {
                jdbc.sql("UPDATE incidents SET next_steps = ? WHERE organization_id = ? AND id = ?")
                        .params(Text.cleanMultiline(u.nextSteps(), 2000), ctx.orgId(), id).update();
            }
            jdbc.sql("UPDATE incidents SET updated_at = ? WHERE organization_id = ? AND id = ?").params(Db.ts(now), ctx.orgId(), id).update();
        });
        return get(ctx, id);
    }

    public Incident.Detail addNote(OrgContext ctx, UUID id, String note) {
        get(ctx, id);
        String text = Text.required(note, 1000, "Write a note.");
        event(ctx.orgId(), id, ctx.userId(), "NOTE", text);
        jdbc.sql("UPDATE incidents SET updated_at = ? WHERE organization_id = ? AND id = ?").params(Db.ts(clock.instant()), ctx.orgId(), id).update();
        return get(ctx, id);
    }

    public void delete(OrgContext ctx, UUID id) {
        WorkspaceService.requireRole(ctx, Role.ADMIN);
        Incident.Detail d = get(ctx, id);
        jdbc.sql("UPDATE mentions SET incident_id = NULL, status = 'REVIEWED' WHERE organization_id = ? AND incident_id = ?").params(ctx.orgId(), id).update();
        jdbc.sql("DELETE FROM incidents WHERE organization_id = ? AND id = ?").params(ctx.orgId(), id).update();
        audit.record(ctx, "incident.deleted", "incident", id, d.summary().title());
    }

    // ---------------------------------------------------------------- from investigations

    /** An investigation linked to the incident finished: record it and refresh the evidence factor. */
    public void investigationCompleted(UUID orgId, UUID incidentId, UUID investigationId, RiskModel.EvidenceState state, String summary) {
        tx.executeWithoutResult(s -> {
            int found = jdbc.sql("SELECT COUNT(*) FROM incidents WHERE organization_id = ? AND id = ?").params(orgId, incidentId)
                    .query(Integer.class).single();
            if (found == 0) {
                return;
            }
            RiskModel.Inputs in = inputs(orgId, incidentId);
            saveInputs(orgId, incidentId, new RiskModel.Inputs(in.severity(), in.sourceType(), in.distinctSites(), in.lastSeenAt(),
                    in.relevance(), state));
            jdbc.sql("UPDATE incidents SET investigation_id = ?, updated_at = ?, status = CASE WHEN status IN ('NEW', 'NEEDS_REVIEW', 'INVESTIGATING') "
                            + "THEN ? ELSE status END WHERE organization_id = ? AND id = ?")
                    .params(investigationId, Db.ts(clock.instant()),
                            state == RiskModel.EvidenceState.UNRESOLVED ? "AWAITING_EVIDENCE" : "INVESTIGATING", orgId, incidentId)
                    .update();
            event(orgId, incidentId, null, "INVESTIGATION", summary);
            recompute(orgId, incidentId);
        });
    }

    public void investigationStarted(UUID orgId, UUID incidentId, UUID actor) {
        jdbc.sql("UPDATE incidents SET status = CASE WHEN status IN ('NEW', 'NEEDS_REVIEW') THEN 'INVESTIGATING' ELSE status END, "
                        + "updated_at = ? WHERE organization_id = ? AND id = ?")
                .params(Db.ts(clock.instant()), orgId, incidentId).update();
        event(orgId, incidentId, actor, "INVESTIGATION", "Investigation started.");
    }

    public void responseDrafted(UUID orgId, UUID incidentId, UUID actor, String kind) {
        jdbc.sql("UPDATE incidents SET status = CASE WHEN status IN ('NEW', 'NEEDS_REVIEW', 'INVESTIGATING', 'AWAITING_EVIDENCE') "
                        + "THEN 'RESPONSE_DRAFTED' ELSE status END, updated_at = ? WHERE organization_id = ? AND id = ?")
                .params(Db.ts(clock.instant()), orgId, incidentId).update();
        event(orgId, incidentId, actor, "RESPONSE", "Draft created: " + kind + ".");
    }

    public boolean exists(OrgContext ctx, UUID id) {
        return jdbc.sql("SELECT COUNT(*) FROM incidents WHERE organization_id = ? AND id = ?").params(ctx.orgId(), id).query(Integer.class).single() > 0;
    }

    // ---------------------------------------------------------------- internals

    private static final String SUMMARY_SELECT = "SELECT i.id, i.title, i.claim, i.status, i.priority, i.risk_score, i.project_id, "
            + "p.name AS project_name, i.owner_id, u.email AS owner_email, i.first_seen_at, i.last_seen_at, i.updated_at, i.investigation_id, "
            + "(SELECT COUNT(*) FROM mentions m WHERE m.incident_id = i.id AND m.organization_id = i.organization_id) AS mention_count "
            + "FROM incidents i LEFT JOIN monitoring_projects p ON p.id = i.project_id AND p.organization_id = i.organization_id "
            + "LEFT JOIN app_users u ON u.id = i.owner_id";

    private static Incident.Summary summary(ResultSet rs) throws SQLException {
        return new Incident.Summary(Db.uuid(rs, "id"), rs.getString("title"), rs.getString("claim"), Incident.Status.valueOf(rs.getString("status")),
                RiskModel.Priority.valueOf(rs.getString("priority")), rs.getInt("risk_score"), Db.uuid(rs, "project_id"), rs.getString("project_name"),
                Db.uuid(rs, "owner_id"), rs.getString("owner_email"), Db.instant(rs, "first_seen_at"), Db.instant(rs, "last_seen_at"),
                Db.instant(rs, "updated_at"), rs.getInt("mention_count"), Db.uuid(rs, "investigation_id"));
    }

    private void insert(UUID id, UUID orgId, UUID projectId, String title, String claim, String url, RiskModel.Assessment risk, UUID actor, Instant now) {
        jdbc.sql("INSERT INTO incidents (id, organization_id, project_id, title, claim, discovery_url, status, priority, risk_score, risk_json, "
                        + "first_seen_at, last_seen_at, created_by, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, 'NEW', ?, ?, ?, ?, ?, ?, ?, ?)")
                .params(id, orgId, projectId, title, Text.clean(claim, 1000), url, risk.priority().name(), risk.score(), json.writeValueAsString(risk),
                        Db.ts(now), Db.ts(now), actor, Db.ts(now), Db.ts(now))
                .update();
    }

    void event(UUID orgId, UUID incidentId, UUID actor, String kind, String detail) {
        jdbc.sql("INSERT INTO incident_events (id, organization_id, incident_id, actor_id, kind, detail, created_at) VALUES (?, ?, ?, ?, ?, ?, ?)")
                .params(UUID.randomUUID(), orgId, incidentId, actor, kind, Text.clean(detail, 1000), Db.ts(clock.instant())).update();
    }

    private RiskModel.Inputs inputs(UUID orgId, UUID id) {
        String j = jdbc.sql("SELECT risk_json FROM incidents WHERE organization_id = ? AND id = ?").params(orgId, id).query(String.class).single();
        return json.readValue(j, RiskModel.Assessment.class).inputs();
    }

    private void saveInputs(UUID orgId, UUID id, RiskModel.Inputs in) {
        RiskModel.Assessment a = RiskModel.assess(in, clock.instant());
        jdbc.sql("UPDATE incidents SET risk_json = ? WHERE organization_id = ? AND id = ?").params(json.writeValueAsString(a), orgId, id).update();
    }

    /** Recomputes the score from stored inputs plus measured spread and recency; keeps a reviewer's priority override. */
    void recompute(UUID orgId, UUID id) {
        RiskModel.Inputs in = inputs(orgId, id);
        int sites = jdbc.sql("SELECT COUNT(DISTINCT domain) FROM mentions WHERE organization_id = ? AND incident_id = ?").params(orgId, id)
                .query(Integer.class).single();
        Instant lastSeen = jdbc.sql("SELECT last_seen_at FROM incidents WHERE organization_id = ? AND id = ?").params(orgId, id)
                .query((rs, i) -> Db.instant(rs, 1)).single();
        RiskModel.Inputs next = new RiskModel.Inputs(in.severity(), in.sourceType(), sites == 0 ? in.distinctSites() : sites,
                lastSeen, in.relevance(), in.evidence());
        RiskModel.Assessment a = RiskModel.assess(next, clock.instant());
        jdbc.sql("UPDATE incidents SET risk_json = ?, risk_score = ?, priority = CASE WHEN priority_override THEN priority ELSE ? END "
                        + "WHERE organization_id = ? AND id = ?")
                .params(json.writeValueAsString(a), a.score(), a.priority().name(), orgId, id).update();
    }

    private static List<String> suggestions(Incident.Summary s, RiskModel.Assessment risk) {
        List<String> out = new ArrayList<>();
        if (s.status() == Incident.Status.RESOLVED) {
            return out;
        }
        if (s.ownerId() == null) {
            out.add("Assign an owner so someone is accountable for the response.");
        }
        RiskModel.EvidenceState ev = risk.inputs().evidence();
        switch (ev == null ? RiskModel.EvidenceState.NOT_INVESTIGATED : ev) {
            case NOT_INVESTIGATED -> out.add("Start an investigation to gather independent evidence for and against the claim.");
            case UNRESOLVED -> out.add("Evidence is insufficient or mixed: look for primary sources (official statements, filings, regulators) "
                    + "and re-run the investigation; avoid definitive public statements until then.");
            case SUPPORTED -> out.add("Available evidence supports the claim: escalate internally and prepare a holding statement before any public response.");
            case CONTRADICTED -> out.add("Available evidence contradicts the claim: consider corrective information citing the sources found.");
            case MISSING_CONTEXT -> out.add("The claim appears outdated or missing context: prepare a response that supplies the context, with citations.");
        }
        if (risk.priority() == RiskModel.Priority.CRITICAL || risk.priority() == RiskModel.Priority.HIGH) {
            out.add("High priority: brief leadership with an internal incident briefing from Response Studio.");
        }
        return out;
    }

    private UUID projectInOrg(OrgContext ctx, String projectId) {
        UUID id = uuidOr404(projectId);
        int n = jdbc.sql("SELECT COUNT(*) FROM monitoring_projects WHERE organization_id = ? AND id = ?").params(ctx.orgId(), id)
                .query(Integer.class).single();
        if (n == 0) {
            throw new ApiException(HttpStatus.NOT_FOUND, "That monitoring project doesn't exist.");
        }
        return id;
    }

    private String email(UUID userId) {
        return jdbc.sql("SELECT email FROM app_users WHERE id = ?").param(userId).query(String.class).optional().orElse("a member");
    }

    private static boolean stronger(String a, String b) {
        return rank(a) > rank(b);
    }

    private static int rank(String level) {
        return level == null ? 0 : switch (level) {
            case "HIGH" -> 3;
            case "MEDIUM" -> 2;
            case "LOW" -> 1;
            default -> 0;
        };
    }

    static SourceType sourceType(String url) {
        String domain = Urls.domain(url);
        return domain == null ? null : SourceType.classify(url, domain, Urls.registrableDomain(domain));
    }

    private static String host(String url) {
        String d = Urls.domain(url);
        return d == null ? "a source" : d;
    }

    static Incident.Status status(String s) {
        try {
            return Incident.Status.valueOf(s.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Unknown incident status.");
        }
    }

    static RiskModel.Priority priority(String s) {
        try {
            return RiskModel.Priority.valueOf(s.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Unknown priority.");
        }
    }

    static String label(Incident.Status s) {
        String n = s.name().replace('_', ' ').toLowerCase(Locale.ROOT);
        return Character.toUpperCase(n.charAt(0)) + n.substring(1);
    }

    private static UUID uuidOr404(String id) {
        try {
            return UUID.fromString(id.trim());
        } catch (IllegalArgumentException e) {
            throw new ApiException(HttpStatus.NOT_FOUND, "Not found.");
        }
    }

    static ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "That incident doesn't exist (it may have been deleted).");
    }
}
