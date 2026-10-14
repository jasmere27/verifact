package com.ai.agent.verifact.intelligence;

import com.ai.agent.verifact.account.Role;
import com.ai.agent.verifact.intelligence.incidents.IncidentService;
import com.ai.agent.verifact.intelligence.monitoring.Mention;
import com.ai.agent.verifact.intelligence.monitoring.MentionStore;
import com.ai.agent.verifact.intelligence.monitoring.MonitoringProject;
import com.ai.agent.verifact.intelligence.monitoring.MonitoringProjectService;
import com.ai.agent.verifact.intelligence.monitoring.MonitoringRun;
import com.ai.agent.verifact.intelligence.monitoring.MonitoringRunner;
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
import java.util.Map;
import java.util.UUID;

/** Monitoring projects, their runs, and the mentions they found. */
@RestController
@RequestMapping("/api/v2/me/orgs/{orgId}")
public class MonitoringController {

    public record StatusRequest(String status) {}

    private final IntelligenceAccess access;
    private final MonitoringProjectService projects;
    private final MonitoringRunner runner;
    private final MentionStore mentions;
    private final IncidentService incidents;

    public MonitoringController(IntelligenceAccess access, MonitoringProjectService projects, MonitoringRunner runner, MentionStore mentions,
                                IncidentService incidents) {
        this.access = access;
        this.projects = projects;
        this.runner = runner;
        this.mentions = mentions;
        this.incidents = incidents;
    }

    @GetMapping("/projects")
    public List<MonitoringProject> list(@AuthenticationPrincipal Jwt jwt, @PathVariable String orgId) {
        return projects.list(access.require(jwt, orgId, Role.MEMBER));
    }

    @PostMapping("/projects")
    public MonitoringProject create(@AuthenticationPrincipal Jwt jwt, @PathVariable String orgId,
                                    @RequestBody(required = false) MonitoringProjectService.ProjectInput in) {
        return projects.create(access.require(jwt, orgId, Role.MEMBER), in);
    }

    @GetMapping("/projects/{id}")
    public MonitoringProject get(@AuthenticationPrincipal Jwt jwt, @PathVariable String orgId, @PathVariable String id) {
        return projects.get(access.require(jwt, orgId, Role.MEMBER), IntelligenceAccess.uuid(id));
    }

    @PutMapping("/projects/{id}")
    public MonitoringProject update(@AuthenticationPrincipal Jwt jwt, @PathVariable String orgId, @PathVariable String id,
                                    @RequestBody(required = false) MonitoringProjectService.ProjectInput in) {
        return projects.update(access.require(jwt, orgId, Role.MEMBER), IntelligenceAccess.uuid(id), in);
    }

    @PostMapping("/projects/{id}/status")
    public MonitoringProject setStatus(@AuthenticationPrincipal Jwt jwt, @PathVariable String orgId, @PathVariable String id,
                                       @RequestBody(required = false) StatusRequest r) {
        MonitoringProject.Status status = r != null && "PAUSED".equalsIgnoreCase(r.status()) ? MonitoringProject.Status.PAUSED : MonitoringProject.Status.ACTIVE;
        return projects.setStatus(access.require(jwt, orgId, Role.MEMBER), IntelligenceAccess.uuid(id), status);
    }

    @DeleteMapping("/projects/{id}")
    public ResponseEntity<Void> delete(@AuthenticationPrincipal Jwt jwt, @PathVariable String orgId, @PathVariable String id) {
        projects.delete(access.require(jwt, orgId, Role.MEMBER), IntelligenceAccess.uuid(id));
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/projects/{id}/run")
    public MonitoringRun runNow(@AuthenticationPrincipal Jwt jwt, @PathVariable String orgId, @PathVariable String id) {
        return runner.runNow(access.require(jwt, orgId, Role.MEMBER), IntelligenceAccess.uuid(id));
    }

    @GetMapping("/runs")
    public List<MonitoringRun> runs(@AuthenticationPrincipal Jwt jwt, @PathVariable String orgId,
                                    @RequestParam(value = "projectId", required = false) String projectId,
                                    @RequestParam(value = "limit", defaultValue = "30") int limit) {
        OrgContext ctx = access.require(jwt, orgId, Role.MEMBER);
        UUID project = projectId == null || projectId.isBlank() ? null : IntelligenceAccess.uuid(projectId);
        return runner.runs(ctx, project, limit);
    }

    @GetMapping("/mentions")
    public Mention.Page mentions(@AuthenticationPrincipal Jwt jwt, @PathVariable String orgId,
                                 @RequestParam(value = "projectId", required = false) String projectId,
                                 @RequestParam(value = "status", required = false) String status,
                                 @RequestParam(value = "relevance", required = false) String relevance,
                                 @RequestParam(value = "withClaim", required = false) Boolean withClaim,
                                 @RequestParam(value = "page", required = false) Integer page,
                                 @RequestParam(value = "size", required = false) Integer size) {
        return mentions.list(access.require(jwt, orgId, Role.MEMBER), projectId, status, relevance, withClaim, page, size);
    }

    @PostMapping("/mentions/{id}/status")
    public Mention mentionStatus(@AuthenticationPrincipal Jwt jwt, @PathVariable String orgId, @PathVariable String id,
                                 @RequestBody(required = false) StatusRequest r) {
        return mentions.setStatus(access.require(jwt, orgId, Role.MEMBER), IntelligenceAccess.uuid(id), r == null ? null : r.status());
    }

    /** Opens an incident from the mention (or links it to the open incident about the same claim). */
    @PostMapping("/mentions/{id}/escalate")
    public Map<String, UUID> escalate(@AuthenticationPrincipal Jwt jwt, @PathVariable String orgId, @PathVariable String id) {
        return Map.of("incidentId", incidents.escalateMention(access.require(jwt, orgId, Role.MEMBER), IntelligenceAccess.uuid(id)));
    }
}
