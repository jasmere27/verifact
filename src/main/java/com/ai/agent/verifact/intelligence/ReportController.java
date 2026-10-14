package com.ai.agent.verifact.intelligence;

import com.ai.agent.verifact.account.Role;
import com.ai.agent.verifact.intelligence.reports.Report;
import com.ai.agent.verifact.intelligence.reports.ReportPdf;
import com.ai.agent.verifact.intelligence.reports.ReportService;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;

/** Executive reports: generate, list, view, PDF. */
@RestController
@RequestMapping("/api/v2/me/orgs/{orgId}/reports")
public class ReportController {

    private final IntelligenceAccess access;
    private final ReportService reports;
    private final ReportPdf pdf;

    public ReportController(IntelligenceAccess access, ReportService reports, ReportPdf pdf) {
        this.access = access;
        this.reports = reports;
        this.pdf = pdf;
    }

    @GetMapping
    public List<Report.Summary> list(@AuthenticationPrincipal Jwt jwt, @PathVariable String orgId) {
        return reports.list(access.require(jwt, orgId, Role.MEMBER));
    }

    @PostMapping
    public Report generate(@AuthenticationPrincipal Jwt jwt, @PathVariable String orgId, @RequestBody(required = false) ReportService.GenerateInput in) {
        return reports.generate(access.require(jwt, orgId, Role.MEMBER), in);
    }

    @GetMapping("/{id}")
    public Report get(@AuthenticationPrincipal Jwt jwt, @PathVariable String orgId, @PathVariable String id) {
        return reports.get(access.require(jwt, orgId, Role.MEMBER), IntelligenceAccess.uuid(id));
    }

    @GetMapping("/{id}/pdf")
    public ResponseEntity<byte[]> pdf(@AuthenticationPrincipal Jwt jwt, @PathVariable String orgId, @PathVariable String id) {
        Report r = reports.get(access.require(jwt, orgId, Role.MEMBER), IntelligenceAccess.uuid(id));
        String name = "verifact-report-" + DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneOffset.UTC).format(r.generatedAt()) + ".pdf";
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(name).build().toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(pdf.render(r));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@AuthenticationPrincipal Jwt jwt, @PathVariable String orgId, @PathVariable String id) {
        reports.delete(access.require(jwt, orgId, Role.MEMBER), IntelligenceAccess.uuid(id));
        return ResponseEntity.noContent().build();
    }
}
