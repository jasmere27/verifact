package com.ai.agent.verifact.research;

import com.ai.agent.verifact.account.AccountService;
import com.ai.agent.verifact.account.SignedInUser;
import com.ai.agent.verifact.common.ApiException;
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
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

/**
 * Capstone projects (ADR-21). Under {@code /api/v2/me/**}, so every call needs a signed-in user; projects are
 * only ever found by owner. Library keys (DOIs contain "/") travel in bodies and query parameters, not paths.
 */
@RestController
@RequestMapping("/api/v2/me/projects")
public class ResearchProjectController {

    public record CreateRequest(String title, String field, String country) {}

    public record ImportRequest(String workspaceId, String editToken) {}

    private final ResearchProjectStore projects;
    private final ResearchWorkspaceStore workspaces;
    private final AccountService accounts;
    private final DocumentExtractor extractor;
    private final DraftAnalysisService drafts;
    private final PaperAnalysisService papers;
    private final ResearchInsightsService insights;

    public ResearchProjectController(ResearchProjectStore projects, ResearchWorkspaceStore workspaces, AccountService accounts,
                                     DocumentExtractor extractor, DraftAnalysisService drafts, PaperAnalysisService papers,
                                     ResearchInsightsService insights) {
        this.projects = projects;
        this.workspaces = workspaces;
        this.accounts = accounts;
        this.extractor = extractor;
        this.drafts = drafts;
        this.papers = papers;
        this.insights = insights;
    }

    @GetMapping
    public List<ResearchProject.Summary> list(@AuthenticationPrincipal Jwt jwt) {
        return projects.list(user(jwt).id());
    }

    @PostMapping
    public ResearchProject create(@AuthenticationPrincipal Jwt jwt, @RequestBody(required = false) CreateRequest r) {
        if (r == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Enter your research topic or working title.");
        }
        SignedInUser user = provisioned(jwt);
        return projects.create(user.id(), r.title(), r.field(), r.country());
    }

    /** Copies a quick workspace (needs its edit token) into a new project; the workspace itself is left as it is. */
    @PostMapping("/import")
    public ResearchProject importWorkspace(@AuthenticationPrincipal Jwt jwt, @RequestBody(required = false) ImportRequest r) {
        if (r == null || r.workspaceId() == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Missing workspace.");
        }
        UUID workspaceId;
        try {
            workspaceId = UUID.fromString(r.workspaceId());
        } catch (IllegalArgumentException e) {
            throw ResearchWorkspaceStore.notFound();
        }
        ResearchWorkspace ws = workspaces.editable(workspaceId, r.editToken());
        SignedInUser user = provisioned(jwt);
        return projects.importWorkspace(user.id(), ws);
    }

    @GetMapping("/{id}")
    public ResearchProject get(@AuthenticationPrincipal Jwt jwt, @PathVariable("id") String id) {
        return projects.get(user(jwt).id(), uuid(id));
    }

    @PutMapping("/{id}")
    public ResearchProject update(@AuthenticationPrincipal Jwt jwt, @PathVariable("id") String id,
                                  @RequestBody(required = false) ResearchProjectStore.Update update) {
        if (update == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Missing changes.");
        }
        return projects.update(user(jwt).id(), uuid(id), update);
    }

    @PostMapping("/{id}/library")
    public ResearchProject addSource(@AuthenticationPrincipal Jwt jwt, @PathVariable("id") String id,
                                     @RequestBody(required = false) ResearchProjectStore.NewItem item) {
        return projects.addSource(user(jwt).id(), uuid(id), item);
    }

    @PutMapping("/{id}/library")
    public ResearchProject updateItem(@AuthenticationPrincipal Jwt jwt, @PathVariable("id") String id,
                                      @RequestBody(required = false) ResearchProjectStore.ItemUpdate update) {
        return projects.updateItem(user(jwt).id(), uuid(id), update);
    }

    @DeleteMapping("/{id}/library")
    public ResearchProject removeItem(@AuthenticationPrincipal Jwt jwt, @PathVariable("id") String id, @RequestParam("key") String key) {
        return projects.removeItem(user(jwt).id(), uuid(id), key);
    }

    /**
     * Upload a chapter draft or a research paper (PDF, DOCX, PPTX, TXT; 10 MB), ADR-23. Only the extracted text and
     * the analysis are kept. Drafts: statements needing citations and the reference check; papers: the index record
     * and a plain-language reading with the paper's own words.
     */
    @PostMapping("/{id}/files")
    public ResearchProject uploadFile(@AuthenticationPrincipal Jwt jwt, @PathVariable("id") String id,
                                      @RequestParam("file") MultipartFile file, @RequestParam("kind") ProjectFile.Kind kind,
                                      @RequestParam(value = "label", required = false) String label) {
        UUID owner = user(jwt).id();
        UUID uuid = uuid(id);
        projects.requireRoomForFile(owner, uuid); // before reading the file or calling the model
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "The file couldn't be read. Please try again.");
        }
        DocumentExtractor.Extracted doc = extractor.extract(bytes);
        if (kind == ProjectFile.Kind.DRAFT) {
            Draft draft = drafts.analyze(file.getOriginalFilename(), doc);
            return projects.addFile(owner, uuid, kind, label, file.getOriginalFilename(), doc, draft, null);
        }
        String country = projects.get(owner, uuid).country();
        PaperAnalysis paper = papers.analyze(doc, country);
        return projects.addFile(owner, uuid, kind, label, file.getOriginalFilename(), doc, null, paper);
    }

    @GetMapping("/{id}/files/{fileId}")
    public ProjectFile file(@AuthenticationPrincipal Jwt jwt, @PathVariable("id") String id, @PathVariable("fileId") String fileId) {
        return projects.file(user(jwt).id(), uuid(id), uuid(fileId));
    }

    public record FileLabel(String label) {}

    @PutMapping("/{id}/files/{fileId}")
    public ResearchProject relabelFile(@AuthenticationPrincipal Jwt jwt, @PathVariable("id") String id, @PathVariable("fileId") String fileId,
                                       @RequestBody(required = false) FileLabel body) {
        return projects.relabelFile(user(jwt).id(), uuid(id), uuid(fileId), body == null ? null : body.label());
    }

    @DeleteMapping("/{id}/files/{fileId}")
    public ResearchProject deleteFile(@AuthenticationPrincipal Jwt jwt, @PathVariable("id") String id, @PathVariable("fileId") String fileId) {
        return projects.deleteFile(user(jwt).id(), uuid(id), uuid(fileId));
    }

    /** Gaps, relations and framework variables from the library's abstracts (same analysis as workspaces). */
    @PostMapping("/{id}/insights")
    public ResearchProject generateInsights(@AuthenticationPrincipal Jwt jwt, @PathVariable("id") String id) {
        UUID owner = user(jwt).id();
        UUID uuid = uuid(id);
        return projects.setInsights(owner, uuid, insights.generate(projects.asWorkspace(owner, uuid)));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@AuthenticationPrincipal Jwt jwt, @PathVariable("id") String id) {
        projects.delete(user(jwt).id(), uuid(id));
        return ResponseEntity.noContent().build();
    }

    private static SignedInUser user(Jwt jwt) {
        return SignedInUser.from(jwt);
    }

    /** The account row must exist before a project can reference it. */
    private SignedInUser provisioned(Jwt jwt) {
        SignedInUser user = user(jwt);
        accounts.ensureProvisioned(user);
        return user;
    }

    private static UUID uuid(String id) {
        try {
            return UUID.fromString(id);
        } catch (IllegalArgumentException e) {
            throw ResearchProjectStore.notFound();
        }
    }
}
