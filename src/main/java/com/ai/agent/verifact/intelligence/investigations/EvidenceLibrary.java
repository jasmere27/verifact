package com.ai.agent.verifact.intelligence.investigations;

import com.ai.agent.verifact.common.ApiException;
import com.ai.agent.verifact.intelligence.Db;
import com.ai.agent.verifact.intelligence.IntelligenceAccess;
import com.ai.agent.verifact.intelligence.OrgContext;
import com.ai.agent.verifact.intelligence.Text;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/** Every source retrieved by this organisation's investigations, searchable, with the verified excerpts used. */
@Service
public class EvidenceLibrary {

    public record Record(UUID id, UUID investigationId, String investigationTitle, int version, String sourceRef, String url,
                         String domain, String title, String snippet, String publishedDate, Instant retrievedAt, String sourceType,
                         String direction, String claimRefs, String excerpt) {}

    public record Page(List<Record> items, int page, int size, long total) {}

    private final JdbcClient jdbc;

    public EvidenceLibrary(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** By default only each investigation's current version, so re-runs don't list a source twice. */
    public Page list(OrgContext ctx, String q, String direction, String sourceType, String investigationId, boolean allVersions,
                     Integer page, Integer size) {
        StringBuilder where = new StringBuilder(" WHERE e.organization_id = ?");
        List<Object> params = new ArrayList<>(List.of(ctx.orgId()));
        if (!allVersions) {
            where.append(" AND e.version = v.current_version");
        }
        if (direction != null && !direction.isBlank()) {
            String d = direction.trim().toUpperCase(Locale.ROOT);
            if (!Set.of("SUPPORTS", "CONTRADICTS", "MIXED", "CONTEXT").contains(d)) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "Unknown direction.");
            }
            where.append(" AND e.direction = ?");
            params.add(d);
        }
        if (sourceType != null && !sourceType.isBlank()) {
            where.append(" AND e.source_type = ?");
            params.add(Text.clean(sourceType, 24).toUpperCase(Locale.ROOT));
        }
        if (investigationId != null && !investigationId.isBlank()) {
            where.append(" AND e.investigation_id = ?");
            params.add(IntelligenceAccess.uuid(investigationId));
        }
        String query = Text.clean(q, 100);
        if (query != null) {
            String like = "%" + query.toLowerCase(Locale.ROOT).replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
            where.append(" AND (LOWER(e.title) LIKE ? ESCAPE '\\' OR LOWER(e.domain) LIKE ? ESCAPE '\\' OR LOWER(e.excerpt) LIKE ? ESCAPE '\\')");
            params.add(like);
            params.add(like);
            params.add(like);
        }
        String from = " FROM evidence_records e JOIN investigations v ON v.id = e.investigation_id AND v.organization_id = e.organization_id";
        int sz = Math.min(Math.max(size == null ? 25 : size, 1), 100);
        int pg = Math.max(page == null ? 0 : page, 0);
        long total = jdbc.sql("SELECT COUNT(*)" + from + where).params(params).query(Long.class).single();
        List<Object> p2 = new ArrayList<>(params);
        p2.add(sz);
        p2.add(pg * sz);
        List<Record> items = jdbc.sql("SELECT e.*, v.title AS investigation_title" + from + where
                        + " ORDER BY e.retrieved_at DESC, e.source_ref LIMIT ? OFFSET ?")
                .params(p2)
                .query((rs, i) -> new Record(Db.uuid(rs, "id"), Db.uuid(rs, "investigation_id"), rs.getString("investigation_title"),
                        rs.getInt("version"), rs.getString("source_ref"), rs.getString("url"), rs.getString("domain"), rs.getString("title"),
                        rs.getString("snippet"), rs.getString("published_date"), Db.instant(rs, "retrieved_at"), rs.getString("source_type"),
                        rs.getString("direction"), rs.getString("claim_refs"), rs.getString("excerpt")))
                .list();
        return new Page(items, pg, sz, total);
    }
}
