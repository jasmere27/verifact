package com.ai.agent.verifact.intelligence;

import com.ai.agent.verifact.account.Role;
import com.ai.agent.verifact.intelligence.incidents.Incident;
import com.ai.agent.verifact.intelligence.incidents.IncidentService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Incidents: list with filters and paging, detail, updates, notes. */
@RestController
@RequestMapping("/api/v2/me/orgs/{orgId}/incidents")
public class IncidentController {

    public record NoteRequest(String note) {}

    private final IntelligenceAccess access;
    private final IncidentService incidents;

    public IncidentController(IntelligenceAccess access, IncidentService incidents) {
        this.access = access;
        this.incidents = incidents;
    }

    @GetMapping
    public Incident.Page list(@AuthenticationPrincipal Jwt jwt, @PathVariable String orgId,
                              @RequestParam(value = "status", required = false) String status,
                              @RequestParam(value = "priority", required = false) String priority,
                              @RequestParam(value = "projectId", required = false) String projectId,
                              @RequestParam(value = "ownerId", required = false) String ownerId,
                              @RequestParam(value = "q", required = false) String q,
                              @RequestParam(value = "sort", required = false) String sort,
                              @RequestParam(value = "page", required = false) Integer page,
                              @RequestParam(value = "size", required = false) Integer size) {
        return incidents.list(access.require(jwt, orgId, Role.MEMBER),
                new IncidentService.Filter(status, priority, projectId, ownerId, q, sort, page, size));
    }

    @PostMapping
    public Incident.Detail create(@AuthenticationPrincipal Jwt jwt, @PathVariable String orgId,
                                  @RequestBody(required = false) IncidentService.ManualInput in) {
        OrgContext ctx = access.require(jwt, orgId, Role.MEMBER);
        return incidents.get(ctx, incidents.createManual(ctx, in));
    }

    @GetMapping("/{id}")
    public Incident.Detail get(@AuthenticationPrincipal Jwt jwt, @PathVariable String orgId, @PathVariable String id) {
        return incidents.get(access.require(jwt, orgId, Role.MEMBER), IntelligenceAccess.uuid(id));
    }

    @PatchMapping("/{id}")
    public Incident.Detail update(@AuthenticationPrincipal Jwt jwt, @PathVariable String orgId, @PathVariable String id,
                                  @RequestBody(required = false) IncidentService.Update u) {
        return incidents.update(access.require(jwt, orgId, Role.MEMBER), IntelligenceAccess.uuid(id), u);
    }

    @PostMapping("/{id}/notes")
    public Incident.Detail note(@AuthenticationPrincipal Jwt jwt, @PathVariable String orgId, @PathVariable String id,
                                @RequestBody(required = false) NoteRequest r) {
        return incidents.addNote(access.require(jwt, orgId, Role.MEMBER), IntelligenceAccess.uuid(id), r == null ? null : r.note());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@AuthenticationPrincipal Jwt jwt, @PathVariable String orgId, @PathVariable String id) {
        incidents.delete(access.require(jwt, orgId, Role.MEMBER), IntelligenceAccess.uuid(id));
        return ResponseEntity.noContent().build();
    }
}
