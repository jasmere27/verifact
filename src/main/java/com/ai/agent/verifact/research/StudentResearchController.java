package com.ai.agent.verifact.research;

import com.ai.agent.verifact.common.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** ResearchFact Student Research Mode: discovery and workspaces (ADR-15). */
@RestController
@RequestMapping("/api/v2/research")
public class StudentResearchController {

    public record DiscoverRequest(String topic, Discovery.Category category, String text, String country) {}

    public record CreateRequest(String topic, String field, String country) {}

    private final ResearchDiscoveryService discovery;
    private final ResearchWorkspaceStore store;

    public StudentResearchController(ResearchDiscoveryService discovery, ResearchWorkspaceStore store) {
        this.discovery = discovery;
        this.store = store;
    }

    @PostMapping("/discover")
    public Discovery discover(@RequestBody(required = false) DiscoverRequest r) {
        if (r == null || r.topic() == null || r.topic().strip().length() < 5 || r.category() == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Enter your research topic and choose what to find.");
        }
        if (r.topic().length() > 300 || (r.text() != null && r.text().length() > 3000)) {
            throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "Keep the topic under 300 characters and the paragraph under 3,000.");
        }
        return discovery.discover(r.category(), r.topic(), r.text(), r.country());
    }

    @PostMapping("/workspaces")
    public ResearchWorkspace.View create(@RequestBody(required = false) CreateRequest r) {
        if (r == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Enter your research topic or title.");
        }
        return store.create(r.topic(), r.field(), r.country());
    }

    @GetMapping("/workspaces/{id}")
    public ResearchWorkspace.View get(@PathVariable("id") String id) {
        return new ResearchWorkspace.View(store.find(uuid(id)).orElseThrow(ResearchWorkspaceStore::notFound), null);
    }

    @PutMapping("/workspaces/{id}")
    public ResearchWorkspace update(@PathVariable("id") String id, @RequestHeader(value = "X-Edit-Token", required = false) String token,
                                    @RequestBody(required = false) ResearchWorkspaceStore.Update update) {
        if (update == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Missing changes.");
        }
        return store.update(uuid(id), token, update);
    }

    @PostMapping("/workspaces/{id}/sources")
    public ResearchWorkspace addSource(@PathVariable("id") String id, @RequestHeader(value = "X-Edit-Token", required = false) String token,
                                       @RequestBody(required = false) ResearchWorkspaceStore.NewSource source) {
        return store.addSource(uuid(id), token, source);
    }

    @DeleteMapping("/workspaces/{id}")
    public ResponseEntity<Void> delete(@PathVariable("id") String id, @RequestHeader(value = "X-Edit-Token", required = false) String token) {
        store.delete(uuid(id), token);
        return ResponseEntity.noContent().build();
    }

    private static UUID uuid(String id) {
        try {
            return UUID.fromString(id);
        } catch (IllegalArgumentException e) {
            throw ResearchWorkspaceStore.notFound();
        }
    }
}
