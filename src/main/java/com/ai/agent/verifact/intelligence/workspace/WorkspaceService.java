package com.ai.agent.verifact.intelligence.workspace;

import com.ai.agent.verifact.account.Role;
import com.ai.agent.verifact.common.ApiException;
import com.ai.agent.verifact.intelligence.AuditLog;
import com.ai.agent.verifact.intelligence.Db;
import com.ai.agent.verifact.intelligence.OrgContext;
import com.ai.agent.verifact.intelligence.Text;
import com.ai.agent.verifact.intelligence.billing.SubscriptionService;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Team workspaces (organisations) and their members. A personal workspace stays single-user. Members are added
 * by the email they signed up with (no invitation emails yet); seats are limited by the plan.
 */
@Service
public class WorkspaceService {

    static final int MAX_CREATED_PER_USER = 10;
    static final int MAX_NAME = 120;

    public record Member(UUID userId, String email, String displayName, Role role, Instant joinedAt) {}

    public record Workspace(UUID id, String name, boolean personal, Role yourRole, List<Member> members, int seats,
                            SubscriptionService.Subscription subscription) {}

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final SubscriptionService subscriptions;
    private final AuditLog audit;
    private final Clock clock;

    public WorkspaceService(JdbcClient jdbc, TransactionTemplate tx, SubscriptionService subscriptions, AuditLog audit, Clock clock) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.subscriptions = subscriptions;
        this.audit = audit;
        this.clock = clock;
    }

    /** Creates a team workspace with the caller as owner. The caller's account must already be provisioned. */
    public UUID create(UUID userId, String rawName) {
        String name = Text.required(rawName, MAX_NAME, "Enter a workspace name.");
        int created = jdbc.sql("SELECT COUNT(*) FROM organizations WHERE created_by = ?").param(userId).query(Integer.class).single();
        if (created >= MAX_CREATED_PER_USER) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "You can create at most " + MAX_CREATED_PER_USER + " workspaces.");
        }
        UUID id = UUID.randomUUID();
        tx.executeWithoutResult(s -> {
            jdbc.sql("INSERT INTO organizations (id, name, personal_owner_id, created_at, created_by) VALUES (?, ?, NULL, ?, ?)")
                    .params(id, name, Db.ts(clock.instant()), userId).update();
            jdbc.sql("INSERT INTO memberships (organization_id, user_id, role, created_at) VALUES (?, ?, 'OWNER', ?)")
                    .params(id, userId, Db.ts(clock.instant())).update();
        });
        return id;
    }

    public Workspace get(OrgContext ctx) {
        record Row(String name, boolean personal) {}
        Row org = jdbc.sql("SELECT name, personal_owner_id IS NOT NULL FROM organizations WHERE id = ?").param(ctx.orgId())
                .query((rs, i) -> new Row(rs.getString(1), rs.getBoolean(2))).single();
        SubscriptionService.Subscription sub = subscriptions.current(ctx.orgId());
        return new Workspace(ctx.orgId(), org.name(), org.personal(), ctx.role(), members(ctx), seats(org.personal(), sub), sub);
    }

    public List<Member> members(OrgContext ctx) {
        return jdbc.sql("SELECT m.user_id, u.email, u.display_name, m.role, m.created_at FROM memberships m "
                        + "JOIN app_users u ON u.id = m.user_id WHERE m.organization_id = ? ORDER BY m.created_at")
                .param(ctx.orgId())
                .query((rs, i) -> new Member(Db.uuid(rs, "user_id"), rs.getString("email"), rs.getString("display_name"),
                        Role.valueOf(rs.getString("role")), Db.instant(rs, "created_at")))
                .list();
    }

    public void rename(OrgContext ctx, String rawName) {
        requireRole(ctx, Role.ADMIN);
        String name = Text.required(rawName, MAX_NAME, "Enter a workspace name.");
        jdbc.sql("UPDATE organizations SET name = ? WHERE id = ?").params(name, ctx.orgId()).update();
        audit.record(ctx, "workspace.renamed", "workspace", ctx.orgId(), name);
    }

    public Member addMember(OrgContext ctx, String rawEmail, Role role) {
        requireRole(ctx, Role.ADMIN);
        Role granted = role == null ? Role.MEMBER : role;
        if (granted == Role.OWNER && !ctx.atLeast(Role.OWNER)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Only an owner can add another owner.");
        }
        Workspace ws = get(ctx);
        if (ws.personal()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "A personal workspace can't be shared. Create a team workspace instead.");
        }
        if (ws.members().size() >= ws.seats()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "This workspace's plan includes " + ws.seats()
                    + (ws.seats() == 1 ? " seat" : " seats") + ". Upgrade under Subscription to add more people.");
        }
        String email = Text.required(rawEmail, 320, "Enter the person's email address.").toLowerCase(Locale.ROOT);
        UUID userId = jdbc.sql("SELECT id FROM app_users WHERE LOWER(email) = ?").param(email).query(UUID.class).optional()
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                        "No VeriFact account uses that email yet. Ask them to sign up first, then add them again."));
        boolean already = jdbc.sql("SELECT COUNT(*) FROM memberships WHERE organization_id = ? AND user_id = ?")
                .params(ctx.orgId(), userId).query(Integer.class).single() > 0;
        if (already) {
            throw new ApiException(HttpStatus.CONFLICT, "That person is already a member.");
        }
        jdbc.sql("INSERT INTO memberships (organization_id, user_id, role, created_at) VALUES (?, ?, ?, ?)")
                .params(ctx.orgId(), userId, granted.name(), Db.ts(clock.instant())).update();
        audit.record(ctx, "member.added", "user", userId, granted.name());
        return members(ctx).stream().filter(m -> m.userId().equals(userId)).findFirst().orElseThrow();
    }

    public void changeRole(OrgContext ctx, UUID userId, Role role) {
        requireRole(ctx, Role.OWNER);
        if (role == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Choose a role.");
        }
        tx.executeWithoutResult(s -> {
            Role current = role(ctx.orgId(), userId);
            if (current == Role.OWNER && role != Role.OWNER && owners(ctx.orgId()) <= 1) {
                throw new ApiException(HttpStatus.CONFLICT, "A workspace needs at least one owner.");
            }
            jdbc.sql("UPDATE memberships SET role = ? WHERE organization_id = ? AND user_id = ?").params(role.name(), ctx.orgId(), userId).update();
        });
        audit.record(ctx, "member.role_changed", "user", userId, role.name());
    }

    /** Admins remove members and admins; only owners remove owners; anyone may leave (except the last owner). */
    public void removeMember(OrgContext ctx, UUID userId) {
        boolean self = userId.equals(ctx.userId());
        tx.executeWithoutResult(s -> {
            Role target = role(ctx.orgId(), userId);
            if (!self) {
                requireRole(ctx, target == Role.OWNER ? Role.OWNER : Role.ADMIN);
            }
            if (target == Role.OWNER && owners(ctx.orgId()) <= 1) {
                throw new ApiException(HttpStatus.CONFLICT, "A workspace needs at least one owner. Make someone else an owner first.");
            }
            boolean personal = jdbc.sql("SELECT personal_owner_id IS NOT NULL FROM organizations WHERE id = ?").param(ctx.orgId())
                    .query(Boolean.class).single();
            if (personal) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "You can't leave your personal workspace.");
            }
            jdbc.sql("DELETE FROM memberships WHERE organization_id = ? AND user_id = ?").params(ctx.orgId(), userId).update();
            // Open incidents they owned go back to the unassigned queue.
            jdbc.sql("UPDATE incidents SET owner_id = NULL WHERE organization_id = ? AND owner_id = ?").params(ctx.orgId(), userId).update();
        });
        audit.record(ctx, self ? "member.left" : "member.removed", "user", userId, null);
    }

    /** True when {@code userId} belongs to the organisation (for assigning incident owners). */
    public boolean isMember(OrgContext ctx, UUID userId) {
        return jdbc.sql("SELECT COUNT(*) FROM memberships WHERE organization_id = ? AND user_id = ?").params(ctx.orgId(), userId)
                .query(Integer.class).single() > 0;
    }

    private Role role(UUID orgId, UUID userId) {
        return jdbc.sql("SELECT role FROM memberships WHERE organization_id = ? AND user_id = ?").params(orgId, userId)
                .query(String.class).optional().map(Role::valueOf)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "That person isn't a member of this workspace."));
    }

    private int owners(UUID orgId) {
        return jdbc.sql("SELECT COUNT(*) FROM memberships WHERE organization_id = ? AND role = 'OWNER'").param(orgId).query(Integer.class).single();
    }

    private static int seats(boolean personal, SubscriptionService.Subscription sub) {
        if (personal || !sub.entitled()) {
            return 1;
        }
        return sub.plan().seats();
    }

    public static void requireRole(OrgContext ctx, Role required) {
        if (!ctx.atLeast(required)) {
            throw new ApiException(HttpStatus.FORBIDDEN, switch (required) {
                case OWNER -> "Only a workspace owner can do that.";
                case ADMIN -> "Only a workspace owner or admin can do that.";
                case MEMBER -> "You don't have permission to do that in this workspace.";
            });
        }
    }
}
