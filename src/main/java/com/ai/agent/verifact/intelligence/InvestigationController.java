package com.ai.agent.verifact.intelligence;

import com.ai.agent.verifact.account.Role;
import com.ai.agent.verifact.intelligence.investigations.EvidenceLibrary;
import com.ai.agent.verifact.intelligence.investigations.Investigation;
import com.ai.agent.verifact.intelligence.investigations.InvestigationService;
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

/** Investigations (start, re-run, versions, notes) and the Evidence Library. */
@RestController
@RequestMapping("/api/v2/me/orgs/{orgId}")
public class InvestigationController {

    public record NotesRequest(String notes, Boolean markReviewed) {}

    private final IntelligenceAccess access;
    private final InvestigationService investigations;
    private final EvidenceLibrary evidence;

    public InvestigationController(IntelligenceAccess access, InvestigationService investigations, EvidenceLibrary evidence) {
        this.access = access;
        this.investigations = investigations;
        this.evidence = evidence;
    }

    @GetMapping("/investigations")
    public Investigation.Page list(@AuthenticationPrincipal Jwt jwt, @PathVariable String orgId,
                                   @RequestParam(value = "incidentId", required = false) String incidentId,
                                   @RequestParam(value = "page", required = false) Integer page,
                                   @RequestParam(value = "size", required = false) Integer size) {
        return investigations.list(access.require(jwt, orgId, Role.MEMBER), incidentId, page, size);
    }

    @PostMapping("/investigations")
    public Investigation.Detail start(@AuthenticationPrincipal Jwt jwt, @PathVariable String orgId,
                                      @RequestBody(required = false) InvestigationService.StartInput in) {
        return investigations.start(access.require(jwt, orgId, Role.MEMBER), in);
    }

    @GetMapping("/investigations/{id}")
    public Investigation.Detail get(@AuthenticationPrincipal Jwt jwt, @PathVariable String orgId, @PathVariable String id) {
        return investigations.get(access.require(jwt, orgId, Role.MEMBER), IntelligenceAccess.uuid(id));
    }

    @GetMapping("/investigations/{id}/versions/{version}")
    public Investigation.Result version(@AuthenticationPrincipal Jwt jwt, @PathVariable String orgId, @PathVariable String id,
                                        @PathVariable int version) {
        return investigations.getVersion(access.require(jwt, orgId, Role.MEMBER), IntelligenceAccess.uuid(id), version);
    }

    @PostMapping("/investigations/{id}/rerun")
    public Investigation.Detail rerun(@AuthenticationPrincipal Jwt jwt, @PathVariable String orgId, @PathVariable String id) {
        return investigations.rerun(access.require(jwt, orgId, Role.MEMBER), IntelligenceAccess.uuid(id));
    }

    @PutMapping("/investigations/{id}/notes")
    public Investigation.Detail notes(@AuthenticationPrincipal Jwt jwt, @PathVariable String orgId, @PathVariable String id,
                                      @RequestBody(required = false) NotesRequest r) {
        return investigations.updateNotes(access.require(jwt, orgId, Role.MEMBER), IntelligenceAccess.uuid(id),
                r == null ? null : r.notes(), r != null && Boolean.TRUE.equals(r.markReviewed()));
    }

    @DeleteMapping("/investigations/{id}")
    public ResponseEntity<Void> delete(@AuthenticationPrincipal Jwt jwt, @PathVariable String orgId, @PathVariable String id) {
        investigations.delete(access.require(jwt, orgId, Role.MEMBER), IntelligenceAccess.uuid(id));
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/evidence")
    public EvidenceLibrary.Page evidence(@AuthenticationPrincipal Jwt jwt, @PathVariable String orgId,
                                         @RequestParam(value = "q", required = false) String q,
                                         @RequestParam(value = "direction", required = false) String direction,
                                         @RequestParam(value = "sourceType", required = false) String sourceType,
                                         @RequestParam(value = "investigationId", required = false) String investigationId,
                                         @RequestParam(value = "allVersions", defaultValue = "false") boolean allVersions,
                                         @RequestParam(value = "page", required = false) Integer page,
                                         @RequestParam(value = "size", required = false) Integer size) {
        return evidence.list(access.require(jwt, orgId, Role.MEMBER), q, direction, sourceType, investigationId, allVersions, page, size);
    }
}
