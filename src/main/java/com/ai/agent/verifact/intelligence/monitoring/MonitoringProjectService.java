package com.ai.agent.verifact.intelligence.monitoring;

import com.ai.agent.verifact.account.Role;
import com.ai.agent.verifact.common.ApiException;
import com.ai.agent.verifact.intelligence.AuditLog;
import com.ai.agent.verifact.intelligence.Db;
import com.ai.agent.verifact.intelligence.OrgContext;
import com.ai.agent.verifact.intelligence.Text;
import com.ai.agent.verifact.intelligence.billing.Plan;
import com.ai.agent.verifact.intelligence.billing.SubscriptionService;
import com.ai.agent.verifact.intelligence.workspace.WorkspaceService;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Monitoring projects: validation, plan limits, pause/resume. Admins manage projects; members read them. */
@Service
public class MonitoringProjectService {

    static final int MAX_NAME = 120;
    static final int MAX_TERMS_PER_LIST = 25;
    static final int MAX_TERM_CHARS = 80;
    static final int MAX_URL_CHARS = 500;
    static final int MAX_PAGES = 5;
    static final int MAX_INTERVAL_HOURS = 168;
    static final Set<Integer> LOOKBACK_DAYS = Set.of(1, 7, 30);
    static final Set<String> THRESHOLDS = Set.of("HIGH", "MEDIUM", "NONE");

    /** What a client may send; the server validates everything. */
    public record ProjectInput(String name, String brand, List<String> aliases, List<String> products, List<String> executives,
                               List<String> keywords, List<String> topics, List<String> excludedTerms, Boolean webSearch,
                               Boolean newsSearch, List<String> rssFeeds, List<String> pages, Integer lookbackDays,
                               String alertThreshold, Integer checkIntervalHours) {}

    private final JdbcClient jdbc;
    private final JsonMapper json;
    private final SubscriptionService subscriptions;
    private final AuditLog audit;
    private final Clock clock;

    public MonitoringProjectService(JdbcClient jdbc, JsonMapper json, SubscriptionService subscriptions, AuditLog audit, Clock clock) {
        this.jdbc = jdbc;
        this.json = json;
        this.subscriptions = subscriptions;
        this.audit = audit;
        this.clock = clock;
    }

    public List<MonitoringProject> list(OrgContext ctx) {
        return jdbc.sql("SELECT * FROM monitoring_projects WHERE organization_id = ? ORDER BY created_at")
                .param(ctx.orgId()).query((rs, i) -> map(rs)).list();
    }

    public MonitoringProject get(OrgContext ctx, UUID id) {
        return find(ctx.orgId(), id).orElseThrow(MonitoringProjectService::notFound);
    }

    Optional<MonitoringProject> find(UUID orgId, UUID id) {
        return jdbc.sql("SELECT * FROM monitoring_projects WHERE organization_id = ? AND id = ?")
                .params(orgId, id).query((rs, i) -> map(rs)).optional();
    }

    public MonitoringProject create(OrgContext ctx, ProjectInput in) {
        WorkspaceService.requireRole(ctx, Role.ADMIN);
        Plan plan = subscriptions.requirePlan(ctx.orgId());
        int existing = jdbc.sql("SELECT COUNT(*) FROM monitoring_projects WHERE organization_id = ?").param(ctx.orgId())
                .query(Integer.class).single();
        if (existing >= plan.maxProjects()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "The " + plan.name() + " plan includes " + plan.maxProjects()
                    + (plan.maxProjects() == 1 ? " monitoring project" : " monitoring projects") + ". Upgrade under Subscription to add more.");
        }
        Validated v = validate(in, plan);
        UUID id = UUID.randomUUID();
        Instant now = clock.instant();
        jdbc.sql("INSERT INTO monitoring_projects (id, organization_id, name, brand, config_json, status, check_interval_hours, "
                        + "next_run_at, created_by, created_at, updated_at) VALUES (?, ?, ?, ?, ?, 'ACTIVE', ?, ?, ?, ?, ?)")
                .params(id, ctx.orgId(), v.name(), v.brand(), json.writeValueAsString(v.config()), v.interval(),
                        Db.ts(now), ctx.userId(), Db.ts(now), Db.ts(now))
                .update();
        audit.record(ctx, "monitoring.project_created", "monitoring_project", id, v.name());
        return get(ctx, id);
    }

    public MonitoringProject update(OrgContext ctx, UUID id, ProjectInput in) {
        WorkspaceService.requireRole(ctx, Role.ADMIN);
        MonitoringProject current = get(ctx, id);
        Plan plan = subscriptions.requirePlan(ctx.orgId());
        Validated v = validate(in, plan);
        // A shorter interval takes effect from the last run, not after the old, longer wait.
        Instant base = current.lastRunAt() == null ? clock.instant() : current.lastRunAt();
        Instant next = current.status() == MonitoringProject.Status.ACTIVE ? earliest(current.nextRunAt(), base.plus(Duration.ofHours(v.interval()))) : null;
        jdbc.sql("UPDATE monitoring_projects SET name = ?, brand = ?, config_json = ?, check_interval_hours = ?, next_run_at = ?, updated_at = ? "
                        + "WHERE organization_id = ? AND id = ?")
                .params(v.name(), v.brand(), json.writeValueAsString(v.config()), v.interval(), Db.ts(next), Db.ts(clock.instant()), ctx.orgId(), id)
                .update();
        audit.record(ctx, "monitoring.project_updated", "monitoring_project", id, v.name());
        return get(ctx, id);
    }

    public MonitoringProject setStatus(OrgContext ctx, UUID id, MonitoringProject.Status status) {
        WorkspaceService.requireRole(ctx, Role.ADMIN);
        get(ctx, id);
        if (status == MonitoringProject.Status.ACTIVE) {
            subscriptions.requirePlan(ctx.orgId());
        }
        Instant now = clock.instant();
        jdbc.sql("UPDATE monitoring_projects SET status = ?, next_run_at = ?, consecutive_failures = 0, updated_at = ? "
                        + "WHERE organization_id = ? AND id = ?")
                .params(status.name(), status == MonitoringProject.Status.ACTIVE ? Db.ts(now) : null, Db.ts(now), ctx.orgId(), id)
                .update();
        audit.record(ctx, status == MonitoringProject.Status.ACTIVE ? "monitoring.project_resumed" : "monitoring.project_paused",
                "monitoring_project", id, null);
        return get(ctx, id);
    }

    public void delete(OrgContext ctx, UUID id) {
        WorkspaceService.requireRole(ctx, Role.ADMIN);
        MonitoringProject p = get(ctx, id);
        jdbc.sql("DELETE FROM monitoring_projects WHERE organization_id = ? AND id = ?").params(ctx.orgId(), id).update();
        audit.record(ctx, "monitoring.project_deleted", "monitoring_project", id, p.name());
    }

    // ---------------------------------------------------------------- validation

    record Validated(String name, String brand, MonitoringConfig config, int interval) {}

    Validated validate(ProjectInput in, Plan plan) {
        if (in == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Missing project details.");
        }
        String brand = Text.required(in.brand(), MAX_NAME, "Enter the brand or company name to monitor.");
        String name = Text.clean(in.name(), MAX_NAME);
        List<String> rss = urls(in.rssFeeds(), plan.maxRssFeedsPerProject(), "RSS feeds");
        List<String> pages = urls(in.pages(), Math.min(MAX_PAGES, Math.max(plan.maxRssFeedsPerProject(), 1)), "watched pages");
        boolean web = Boolean.TRUE.equals(in.webSearch());
        boolean news = Boolean.TRUE.equals(in.newsSearch());
        if (!web && !news && rss.isEmpty() && pages.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Choose at least one source to monitor.");
        }
        int lookback = in.lookbackDays() == null ? 7 : in.lookbackDays();
        if (!LOOKBACK_DAYS.contains(lookback)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Look back 1, 7 or 30 days.");
        }
        String threshold = in.alertThreshold() == null ? "HIGH" : in.alertThreshold().trim().toUpperCase(Locale.ROOT);
        if (!THRESHOLDS.contains(threshold)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Choose when incidents open: HIGH, MEDIUM or NONE.");
        }
        MonitoringConfig config = new MonitoringConfig(
                terms(in.aliases(), "aliases"), terms(in.products(), "products"), terms(in.executives(), "executives"),
                terms(in.keywords(), "keywords"), terms(in.topics(), "topics"), terms(in.excludedTerms(), "excluded terms"),
                new MonitoringConfig.Sources(web, news, rss, pages), lookback, threshold);
        if (config.termCount() > plan.maxTermsPerProject()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "The " + plan.name() + " plan allows " + plan.maxTermsPerProject()
                    + " terms per project (aliases, products, people, keywords, topics and exclusions together). You entered "
                    + config.termCount() + ".");
        }
        int interval = in.checkIntervalHours() == null ? Math.max(24, plan.minCheckIntervalHours()) : in.checkIntervalHours();
        if (interval < plan.minCheckIntervalHours()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "The " + plan.name() + " plan checks at most every "
                    + plan.minCheckIntervalHours() + " hours.");
        }
        if (interval > MAX_INTERVAL_HOURS) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Check at least once a week.");
        }
        return new Validated(name == null ? brand : name, brand, config, interval);
    }

    private static List<String> terms(List<String> raw, String what) {
        return Text.terms(raw, MAX_TERMS_PER_LIST, MAX_TERM_CHARS, what);
    }

    /** Syntax only here: every fetch re-checks the destination (and each redirect) with UrlGuard. */
    static List<String> urls(List<String> raw, int max, String what) {
        List<String> list = Text.terms(raw, Integer.MAX_VALUE, MAX_URL_CHARS + 1, what);
        if (list.size() > max) {
            throw new ApiException(HttpStatus.FORBIDDEN, max == 0 ? "Your plan doesn't include " + what + "."
                    : "At most " + max + " " + what + " on your plan.");
        }
        for (String u : list) {
            URI uri;
            try {
                uri = URI.create(u);
            } catch (IllegalArgumentException e) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "\"" + Text.clean(u, 80) + "\" isn't a valid link.");
            }
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            if (u.length() > MAX_URL_CHARS || !(scheme.equals("http") || scheme.equals("https")) || uri.getHost() == null
                    || uri.getUserInfo() != null) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "\"" + Text.clean(u, 80) + "\" isn't a public web link (http or https).");
            }
        }
        return list;
    }

    // ---------------------------------------------------------------- mapping

    MonitoringProject map(ResultSet rs) throws SQLException {
        MonitoringProject.Status status = MonitoringProject.Status.valueOf(rs.getString("status"));
        int failures = rs.getInt("consecutive_failures");
        Instant lastRun = Db.instant(rs, "last_run_at");
        Instant lastSuccess = Db.instant(rs, "last_success_at");
        String health = status == MonitoringProject.Status.PAUSED ? "PAUSED"
                : lastRun == null ? "NEVER_RUN"
                : failures >= 3 ? "FAILING"
                : failures > 0 || (lastSuccess == null || lastSuccess.isBefore(lastRun)) ? "DEGRADED"
                : "HEALTHY";
        return new MonitoringProject(Db.uuid(rs, "id"), rs.getString("name"), rs.getString("brand"),
                json.readValue(rs.getString("config_json"), MonitoringConfig.class), status, rs.getInt("check_interval_hours"),
                Db.instant(rs, "next_run_at"), lastRun, lastSuccess, failures, Db.instant(rs, "created_at"), Db.instant(rs, "updated_at"), health);
    }

    private static Instant earliest(Instant a, Instant b) {
        return a == null ? b : a.isBefore(b) ? a : b;
    }

    static ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "That monitoring project doesn't exist (it may have been deleted).");
    }
}
