package com.ai.agent.verifact.research;

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
 * ResearchFact API. Text is checked and returned, never stored. The stream variant sends the same
 * events as VeriFact: {@code stage}, {@code claims}, {@code sources}, then {@code result} or {@code error}.
 */
@RestController
@RequestMapping("/api/v2/research")
public class ResearchController {

    static final int MIN_CHARS = 30;

    public record CheckRequest(String text) {}

    private final ResearchCheckService service;
    private final VerificationStreamer streamer;
    private final int maxInputChars;

    public ResearchController(ResearchCheckService service, VerificationStreamer streamer,
                              @Value("${app.input.max-chars:10000}") int maxInputChars) {
        this.service = service;
        this.streamer = streamer;
        this.maxInputChars = maxInputChars;
    }

    @PostMapping("/check")
    public ResearchCheck check(@RequestBody(required = false) CheckRequest request) {
        return service.check(validText(request), VerificationProgress.NONE);
    }

    @PostMapping(value = "/check/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter checkStream(@RequestBody(required = false) CheckRequest request, HttpServletResponse response) {
        String text = validText(request);
        response.setHeader("X-Accel-Buffering", "no");
        response.setHeader("Cache-Control", "no-cache");
        return streamer.start(progress -> service.check(text, progress));
    }

    private String validText(CheckRequest request) {
        String text = request == null ? null : request.text();
        if (text == null || text.strip().length() < MIN_CHARS) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "Paste text that cites research: a paragraph with in-text citations and its reference list, or DOIs.");
        }
        if (text.length() > maxInputChars) {
            throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE,
                    "That text is too long. Please keep it under " + maxInputChars + " characters.");
        }
        return text;
    }
}
