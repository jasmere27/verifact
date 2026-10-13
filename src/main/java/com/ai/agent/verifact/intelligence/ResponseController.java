package com.ai.agent.verifact.intelligence;

import com.ai.agent.verifact.account.Role;
import com.ai.agent.verifact.intelligence.responses.DraftKind;
import com.ai.agent.verifact.intelligence.responses.ResponseDraft;
import com.ai.agent.verifact.intelligence.responses.ResponseStudioService;
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

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/** Response Studio drafts. */
@RestController
@RequestMapping("/api/v2/me/orgs/{orgId}/responses")
public class ResponseController {

    public record StatusRequest(String status) {}

    private final IntelligenceAccess access;
    private final ResponseStudioService studio;

    public ResponseController(IntelligenceAccess access, ResponseStudioService studio) {
        this.access = access;
        this.studio = studio;
    }

    @GetMapping("/kinds")
    public List<Map<String, String>> kinds() {
        return Arrays.stream(DraftKind.values()).map(k -> Map.of("id", k.name(), "label", k.label())).toList();
    }

    @GetMapping
    public List<ResponseDraft.Summary> list(@AuthenticationPrincipal Jwt jwt, @PathVariable String orgId,
                                            @RequestParam(value = "investigationId", required = false) String investigationId,
                                            @RequestParam(value = "incidentId", required = false) String incidentId,
                                            @RequestParam(value = "status", required = false) String status) {
        return studio.list(access.require(jwt, orgId, Role.MEMBER), investigationId, incidentId, status);
    }

    @PostMapping
    public ResponseDraft.Detail generate(@AuthenticationPrincipal Jwt jwt, @PathVariable String orgId,
                                         @RequestBody(required = false) ResponseStudioService.GenerateInput in) {
        return studio.generate(access.require(jwt, orgId, Role.MEMBER), in);
    }

    @GetMapping("/{id}")
    public ResponseDraft.Detail get(@AuthenticationPrincipal Jwt jwt, @PathVariable String orgId, @PathVariable String id) {
        return studio.get(access.require(jwt, orgId, Role.MEMBER), IntelligenceAccess.uuid(id));
    }

    @PutMapping("/{id}")
    public ResponseDraft.Detail edit(@AuthenticationPrincipal Jwt jwt, @PathVariable String orgId, @PathVariable String id,
                                     @RequestBody(required = false) ResponseStudioService.Edit e) {
        return studio.edit(access.require(jwt, orgId, Role.MEMBER), IntelligenceAccess.uuid(id), e);
    }

    @PostMapping("/{id}/status")
    public ResponseDraft.Detail status(@AuthenticationPrincipal Jwt jwt, @PathVariable String orgId, @PathVariable String id,
                                       @RequestBody(required = false) StatusRequest r) {
        return studio.setStatus(access.require(jwt, orgId, Role.MEMBER), IntelligenceAccess.uuid(id), r == null ? null : r.status());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@AuthenticationPrincipal Jwt jwt, @PathVariable String orgId, @PathVariable String id) {
        studio.delete(access.require(jwt, orgId, Role.MEMBER), IntelligenceAccess.uuid(id));
        return ResponseEntity.noContent().build();
    }
}
