package com.ai.agent.verifact.intelligence.monitoring;

import com.ai.agent.verifact.common.ApiException;
import com.ai.agent.verifact.evidence.Grounding;
import com.ai.agent.verifact.evidence.Urls;
import com.ai.agent.verifact.intelligence.Db;
import com.ai.agent.verifact.intelligence.OrgContext;
import com.ai.agent.verifact.intelligence.Text;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Mentions, de-duplicated per project by normalised URL (tracking parameters removed) and flagged as copies when
 * another URL already carried the same headline and text (syndicated or copied reporting).
 */
@Component
public class MentionStore {

    private static final Pattern TRACKING = Pattern.compile("^(utm_[a-z]+|fbclid|gclid|mc_cid|mc_eid|ref|ref_src|igshid)$");
    static final Set<String> STATUSES = Set.of("NEW", "REVIEWED", "DISMISSED");

    /**
     * @param copyOf         true when the same text was already seen at another URL in this project
     * @param copyIncidentId the incident the earlier copy belongs to, if any
     */
    public record Upserted(UUID id, boolean isNew, boolean copyOf, UUID copyIncidentId) {}

    private final JdbcClient jdbc;
    private final JsonMapper json;
    private final Clock clock;

    public MentionStore(JdbcClient jdbc, JsonMapper json, Clock clock) {
        this.jdbc = jdbc;
        this.json = json;
        this.clock = clock;
    }

    public Upserted upsert(UUID orgId, UUID projectId, UUID runId, String connector, SourceConnector.Item item, Relevance.Result relevance) {
        String key = urlKey(item.url());
        if (key == null) {
            return null;
        }
        Instant now = clock.instant();
        UUID existing = jdbc.sql("SELECT id FROM mentions WHERE project_id = ? AND url_key = ?").params(projectId, key)
                .query(UUID.class).optional().orElse(null);
        if (existing != null) {
            jdbc.sql("UPDATE mentions SET last_seen_at = ?, seen_count = seen_count + 1 WHERE organization_id = ? AND id = ?")
                    .params(Db.ts(now), orgId, existing).update();
            return new Upserted(existing, false, false, null);
        }
        String title = Text.clean(item.title(), 400);
        String snippet = Text.clean(item.snippet(), 1200);
        String hash = contentHash(title, snippet);
        boolean copy = jdbc.sql("SELECT COUNT(*) FROM mentions WHERE project_id = ? AND content_hash = ?").params(projectId, hash)
                .query(Integer.class).single() > 0;
        UUID copyIncident = !copy ? null : jdbc.sql("SELECT incident_id FROM mentions WHERE project_id = ? AND content_hash = ? "
                        + "AND incident_id IS NOT NULL ORDER BY first_seen_at LIMIT 1")
                .params(projectId, hash).query(UUID.class).optional().orElse(null);
        UUID id = UUID.randomUUID();
        try {
            jdbc.sql("INSERT INTO mentions (id, organization_id, project_id, first_run_id, connector, url, url_key, content_hash, domain, title, "
                            + "snippet, published_date, relevance, matched_json, status, first_seen_at, last_seen_at) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")
                    .params(id, orgId, projectId, runId, connector, Text.clean(item.url(), 2048), key, hash, Urls.domain(item.url()), title, snippet,
                            Text.clean(item.publishedDate(), 40), relevance.level().name(), json.writeValueAsString(relevance.matched()),
                            copy ? "REVIEWED" : "NEW", Db.ts(now), Db.ts(now))
                    .update();
        } catch (DuplicateKeyException e) {
            return null; // another connector in this run stored it a moment ago
        }
        return new Upserted(id, true, copy, copyIncident);
    }

    public void setClaim(UUID orgId, UUID id, MentionClaimExtractor.Found found) {
        jdbc.sql("UPDATE mentions SET claim = ?, claim_quote = ?, claim_severity = ? WHERE organization_id = ? AND id = ?")
                .params(Text.clean(found.claim(), 600), Text.clean(found.quote(), 600), found.severity(), orgId, id).update();
    }

    public Mention.Page list(OrgContext ctx, String projectId, String status, String relevance, Boolean withClaim, Integer page, Integer size) {
        StringBuilder where = new StringBuilder(" WHERE m.organization_id = ?");
        List<Object> params = new ArrayList<>(List.of(ctx.orgId()));
        if (projectId != null && !projectId.isBlank()) {
            where.append(" AND m.project_id = ?");
            params.add(com.ai.agent.verifact.intelligence.IntelligenceAccess.uuid(projectId));
        }
        if (status != null && !status.isBlank()) {
            String s = status.trim().toUpperCase(Locale.ROOT);
            if (!Set.of("NEW", "REVIEWED", "DISMISSED", "ESCALATED").contains(s)) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "Unknown mention status.");
            }
            where.append(" AND m.status = ?");
            params.add(s);
        }
        if (relevance != null && !relevance.isBlank()) {
            String r = relevance.trim().toUpperCase(Locale.ROOT);
            if (!Set.of("HIGH", "MEDIUM", "LOW").contains(r)) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "Unknown relevance.");
            }
            where.append(" AND m.relevance = ?");
            params.add(r);
        }
        if (Boolean.TRUE.equals(withClaim)) {
            where.append(" AND m.claim IS NOT NULL");
        }
        int sz = Math.min(Math.max(size == null ? 25 : size, 1), 100);
        int pg = Math.max(page == null ? 0 : page, 0);
        long total = jdbc.sql("SELECT COUNT(*) FROM mentions m" + where).params(params).query(Long.class).single();
        List<Object> p2 = new ArrayList<>(params);
        p2.add(sz);
        p2.add(pg * sz);
        List<Mention> items = jdbc.sql("SELECT m.*, p.name AS project_name FROM mentions m JOIN monitoring_projects p ON p.id = m.project_id"
                        + where + " ORDER BY m.first_seen_at DESC, m.id LIMIT ? OFFSET ?")
                .params(p2).query((rs, i) -> map(rs)).list();
        return new Mention.Page(items, pg, sz, total);
    }

    public Mention get(OrgContext ctx, UUID id) {
        return jdbc.sql("SELECT m.*, p.name AS project_name FROM mentions m JOIN monitoring_projects p ON p.id = m.project_id "
                        + "WHERE m.organization_id = ? AND m.id = ?")
                .params(ctx.orgId(), id).query((rs, i) -> map(rs)).optional()
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "That mention doesn't exist."));
    }

    public Mention setStatus(OrgContext ctx, UUID id, String status) {
        String s = status == null ? "" : status.trim().toUpperCase(Locale.ROOT);
        if (!STATUSES.contains(s)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "A mention can be marked new, reviewed or dismissed (escalate it to open an incident).");
        }
        Mention m = get(ctx, id);
        if (m.incidentId() != null) {
            throw new ApiException(HttpStatus.CONFLICT, "This mention belongs to an incident; manage it there.");
        }
        jdbc.sql("UPDATE mentions SET status = ? WHERE organization_id = ? AND id = ?").params(s, ctx.orgId(), id).update();
        return get(ctx, id);
    }

    private Mention map(ResultSet rs) throws SQLException {
        return new Mention(Db.uuid(rs, "id"), Db.uuid(rs, "project_id"), rs.getString("project_name"), rs.getString("connector"),
                rs.getString("url"), rs.getString("domain"), rs.getString("title"), rs.getString("snippet"), rs.getString("published_date"),
                rs.getString("relevance"), json.readValue(rs.getString("matched_json"), new TypeReference<List<String>>() {}),
                rs.getString("claim"), rs.getString("claim_quote"), rs.getString("claim_severity"), rs.getString("status"),
                Db.uuid(rs, "incident_id"), Db.instant(rs, "first_seen_at"), Db.instant(rs, "last_seen_at"), rs.getInt("seen_count"));
    }

    /** Normalised URL without tracking parameters; a page-watch version marker ({@code #vf-<hash>}) is kept. */
    static String urlKey(String url) {
        String base = Urls.normalizeUrl(url);
        if (base == null) {
            return null;
        }
        int q = base.indexOf('?');
        if (q >= 0) {
            List<String> kept = new ArrayList<>();
            for (String part : base.substring(q + 1).split("&")) {
                String name = part.split("=", 2)[0].toLowerCase(Locale.ROOT);
                if (!part.isBlank() && !TRACKING.matcher(name).matches()) {
                    kept.add(part);
                }
            }
            base = base.substring(0, q) + (kept.isEmpty() ? "" : "?" + String.join("&", kept));
        }
        try {
            String fragment = URI.create(url.trim()).getRawFragment();
            if (fragment != null && fragment.startsWith("vf-")) {
                base = base + "#" + fragment;
            }
        } catch (IllegalArgumentException e) {
            // normalizeUrl accepted it; ignore an odd fragment
        }
        return base.length() > 512 ? base.substring(0, 512) : base;
    }

    static String contentHash(String title, String snippet) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(Grounding.material(title == null ? "" : title, snippet == null ? "" : snippet)
                    .getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(d);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
