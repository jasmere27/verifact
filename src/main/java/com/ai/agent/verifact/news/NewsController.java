package com.ai.agent.verifact.news;

import com.ai.agent.verifact.common.ApiException;
import com.ai.agent.verifact.verification.VerificationProgress;
import com.ai.agent.verifact.verification.VerificationStreamer;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
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
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * NewsFact API: run a check (saved as a workspace), read it by its unguessable id, and update the
 * editor's review with the edit token returned at creation.
 */
@RestController
@RequestMapping("/api/v2/news/checks")
public class NewsController {

    static final int MIN_CHARS = 60;
    static final int MAX_NOTE_CHARS = 1000;
    static final int MAX_DECISIONS = 20;

    public record CheckRequest(String input) {}

    private final NewsCheckService service;
    private final NewsStore store;
    private final VerificationStreamer streamer;
    private final int maxInputChars;

    public NewsController(NewsCheckService service, NewsStore store, VerificationStreamer streamer,
                          @Value("${app.input.max-chars:10000}") int maxInputChars) {
        this.service = service;
        this.store = store;
        this.streamer = streamer;
        this.maxInputChars = maxInputChars;
    }

    @PostMapping
    public NewsWorkspace check(@RequestBody(required = false) CheckRequest request) {
        return store.create(service.check(validInput(request), VerificationProgress.NONE));
    }

    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter checkStream(@RequestBody(required = false) CheckRequest request, HttpServletResponse response) {
        String input = validInput(request);
        response.setHeader("X-Accel-Buffering", "no");
        response.setHeader("Cache-Control", "no-cache");
        return streamer.start(progress -> store.create(service.check(input, progress)));
    }

    @GetMapping("/{id}")
    public NewsWorkspace get(@PathVariable("id") String id) {
        return store.find(uuid(id)).orElseThrow(NewsStore::notFound);
    }

    @PutMapping("/{id}/review")
    public NewsReview updateReview(@PathVariable("id") String id,
                                   @RequestHeader(value = "X-Edit-Token", required = false) String token,
                                   @RequestBody(required = false) NewsReview review) {
        return store.updateReview(uuid(id), token, validReview(review));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable("id") String id,
                                       @RequestHeader(value = "X-Edit-Token", required = false) String token) {
        store.delete(uuid(id), token);
        return ResponseEntity.noContent().build();
    }

    private String validInput(CheckRequest request) {
        String input = request == null ? null : request.input();
        if (input == null || input.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Paste an article link or the article text.");
        }
        String t = input.strip();
        if (!t.matches("(?i)^https?://\\S+$") && t.length() < MIN_CHARS) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Paste the full article (or its link), not a single line.");
        }
        if (input.length() > maxInputChars) {
            throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE,
                    "That text is too long. Please keep it under " + maxInputChars + " characters, or paste the link.");
        }
        return t;
    }

    /** Bounded, well-formed decisions only; claim ids like C1..C20; notes length-capped. */
    static NewsReview validReview(NewsReview review) {
        if (review == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Missing review.");
        }
        Map<String, NewsReview.Decision> decisions = new LinkedHashMap<>();
        if (review.decisions() != null) {
            if (review.decisions().size() > MAX_DECISIONS) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "Too many decisions.");
            }
            review.decisions().forEach((claimId, d) -> {
                if (claimId == null || !claimId.matches("C\\d{1,2}") || d == null || d.status() == null) {
                    throw new ApiException(HttpStatus.BAD_REQUEST, "Invalid review decision.");
                }
                decisions.put(claimId, new NewsReview.Decision(d.status(), cap(d.note())));
            });
        }
        return new NewsReview(decisions, cap(review.editorNote()), null);
    }

    private static String cap(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        String t = s.strip();
        return t.length() > MAX_NOTE_CHARS ? t.substring(0, MAX_NOTE_CHARS) : t;
    }

    private static UUID uuid(String id) {
        try {
            return UUID.fromString(id);
        } catch (IllegalArgumentException e) {
            throw NewsStore.notFound();
        }
    }
}
