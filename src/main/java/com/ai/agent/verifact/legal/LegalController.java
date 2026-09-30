package com.ai.agent.verifact.legal;

import com.ai.agent.verifact.common.ApiException;
import com.ai.agent.verifact.verification.VerificationProgress;
import com.ai.agent.verifact.verification.VerificationStreamer;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * LegalFact API. Case descriptions are analysed and returned, never stored (ADR-12). The stream
 * variant sends the same events as VeriFact's: {@code stage}, {@code claims} (the issue topics),
 * {@code sources}, then one {@code result} or {@code error}.
 */
@RestController
@RequestMapping("/api/v2/legal")
public class LegalController {

    static final int MIN_CHARS = 40;

    public record CaseRequest(String description) {}

    private final CaseIntelligenceService service;
    private final VerificationStreamer streamer;
    private final int maxInputChars;

    public LegalController(CaseIntelligenceService service, VerificationStreamer streamer,
                           @Value("${app.input.max-chars:10000}") int maxInputChars) {
        this.service = service;
        this.streamer = streamer;
        this.maxInputChars = maxInputChars;
    }

    @PostMapping("/case-intelligence")
    public CaseIntelligence analyze(@RequestBody(required = false) CaseRequest request) {
        return service.analyze(validDescription(request), VerificationProgress.NONE);
    }

    @PostMapping(value = "/case-intelligence/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter analyzeStream(@RequestBody(required = false) CaseRequest request, HttpServletResponse response) {
        String description = validDescription(request);
        response.setHeader("X-Accel-Buffering", "no");
        response.setHeader("Cache-Control", "no-cache");
        return streamer.start(progress -> service.analyze(description, progress));
    }

    private String validDescription(CaseRequest request) {
        String description = request == null ? null : request.description();
        if (description == null || description.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Please describe the situation.");
        }
        if (description.strip().length() < MIN_CHARS) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "Please describe the situation in a few sentences: what happened, when, and where.");
        }
        if (description.length() > maxInputChars) {
            throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE,
                    "That description is too long. Please keep it under " + maxInputChars + " characters.");
        }
        return description;
    }
}
