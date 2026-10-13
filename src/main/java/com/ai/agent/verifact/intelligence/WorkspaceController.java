package com.ai.agent.verifact.intelligence;

import com.ai.agent.verifact.account.AccountService;
import com.ai.agent.verifact.account.Role;
import com.ai.agent.verifact.account.SignedInUser;
import com.ai.agent.verifact.common.ApiException;
import com.ai.agent.verifact.intelligence.workspace.WorkspaceService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** Workspaces (organisations), members, the audit log and the Overview dashboard. */
@RestController
@RequestMapping("/api/v2/me/orgs")
public class WorkspaceController {

    public record NameRequest(String name) {}

    public record MemberRequest(String email, String role) {}

    private final IntelligenceAccess access;
    private final AccountService accounts;
    private final WorkspaceService workspaces;
    private final OverviewService overview;
    private final AuditLog audit;
    private final CapabilitiesProbe probe;

    public WorkspaceController(IntelligenceAccess access, AccountService accounts, WorkspaceService workspaces, OverviewService overview,
                               AuditLog audit, CapabilitiesProbe probe) {
        this.probe = probe;
        this.access = access;
        this.accounts = accounts;
        this.workspaces = workspaces;
        this.overview = overview;
        this.audit = audit;
    }

    @PostMapping
    public WorkspaceService.Workspace create(@AuthenticationPrincipal Jwt jwt, @RequestBody(required = false) NameRequest r) {
        SignedInUser user = SignedInUser.from(jwt);
        accounts.ensureProvisioned(user);
        UUID id = workspaces.create(user.id(), r == null ? null : r.name());
        OrgContext ctx = access.require(jwt, id.toString(), Role.OWNER);
        audit.record(ctx, "workspace.created", "workspace", id, null);
        return workspaces.get(ctx);
    }

    @GetMapping("/{orgId}")
    public WorkspaceService.Workspace get(@AuthenticationPrincipal Jwt jwt, @PathVariable String orgId) {
        return workspaces.get(access.require(jwt, orgId, Role.MEMBER));
    }

    @PutMapping("/{orgId}")
    public WorkspaceService.Workspace rename(@AuthenticationPrincipal Jwt jwt, @PathVariable String orgId, @RequestBody(required = false) NameRequest r) {
        OrgContext ctx = access.require(jwt, orgId, Role.ADMIN);
        workspaces.rename(ctx, r == null ? null : r.name());
        return workspaces.get(ctx);
    }

    @GetMapping("/{orgId}/overview")
    public OverviewService.Overview overview(@AuthenticationPrincipal Jwt jwt, @PathVariable String orgId) {
        return overview.get(access.require(jwt, orgId, Role.MEMBER));
    }

    @GetMapping("/{orgId}/members")
    public List<WorkspaceService.Member> members(@AuthenticationPrincipal Jwt jwt, @PathVariable String orgId) {
        return workspaces.members(access.require(jwt, orgId, Role.MEMBER));
    }

    @PostMapping("/{orgId}/members")
    public WorkspaceService.Member addMember(@AuthenticationPrincipal Jwt jwt, @PathVariable String orgId, @RequestBody(required = false) MemberRequest r) {
        OrgContext ctx = access.require(jwt, orgId, Role.ADMIN);
        return workspaces.addMember(ctx, r == null ? null : r.email(), r == null ? null : role(r.role()));
    }

    @PutMapping("/{orgId}/members/{userId}")
    public List<WorkspaceService.Member> changeRole(@AuthenticationPrincipal Jwt jwt, @PathVariable String orgId, @PathVariable String userId,
                                                    @RequestBody(required = false) MemberRequest r) {
        OrgContext ctx = access.require(jwt, orgId, Role.OWNER);
        workspaces.changeRole(ctx, IntelligenceAccess.uuid(userId), r == null ? null : role(r.role()));
        return workspaces.members(ctx);
    }

    @DeleteMapping("/{orgId}/members/{userId}")
    public ResponseEntity<Void> removeMember(@AuthenticationPrincipal Jwt jwt, @PathVariable String orgId, @PathVariable String userId) {
        workspaces.removeMember(access.require(jwt, orgId, Role.MEMBER), IntelligenceAccess.uuid(userId));
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{orgId}/audit")
    public List<AuditLog.Entry> audit(@AuthenticationPrincipal Jwt jwt, @PathVariable String orgId,
                                      @RequestParam(value = "limit", defaultValue = "100") int limit) {
        return audit.recent(access.require(jwt, orgId, Role.ADMIN), limit);
    }

    /** What this deployment can do, so the UI never offers a dead end. */
    @GetMapping("/{orgId}/capabilities")
    public Map<String, Object> capabilities(@AuthenticationPrincipal Jwt jwt, @PathVariable String orgId) {
        access.require(jwt, orgId, Role.MEMBER);
        return probe.describe();
    }

    static Role role(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        try {
            return Role.valueOf(s.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Roles are OWNER, ADMIN or MEMBER.");
        }
    }
}
