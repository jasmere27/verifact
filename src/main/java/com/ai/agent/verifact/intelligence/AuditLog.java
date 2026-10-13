package com.ai.agent.verifact.intelligence;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

/** Records sensitive actions (membership, billing, approvals, deletions) per organisation. */
@Component
public class AuditLog {

    public record Entry(UUID id, UUID actorId, String actorEmail, String action, String targetType, String targetId,
                        String detail, Instant createdAt) {}

    private final JdbcClient jdbc;
    private final Clock clock;

    public AuditLog(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public void record(OrgContext ctx, String action, String targetType, Object targetId, String detail) {
        jdbc.sql("INSERT INTO audit_events (id, organization_id, actor_id, action, target_type, target_id, detail, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)")
                .params(UUID.randomUUID(), ctx.orgId(), ctx.userId(), action, targetType,
                        targetId == null ? null : targetId.toString(), Text.clean(detail, 500), now())
                .update();
    }

    public List<Entry> recent(OrgContext ctx, int limit) {
        return jdbc.sql("SELECT a.id, a.actor_id, u.email, a.action, a.target_type, a.target_id, a.detail, a.created_at "
                        + "FROM audit_events a LEFT JOIN app_users u ON u.id = a.actor_id "
                        + "WHERE a.organization_id = ? ORDER BY a.created_at DESC LIMIT ?")
                .params(ctx.orgId(), Math.min(Math.max(limit, 1), 200))
                .query((rs, i) -> new Entry(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3),
                        rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7), Db.instant(rs, 8)))
                .list();
    }

    private OffsetDateTime now() {
        return OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }
}
