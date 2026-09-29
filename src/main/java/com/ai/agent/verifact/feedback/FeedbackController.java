package com.ai.agent.verifact.feedback;

import com.ai.agent.verifact.common.ApiException;
import com.ai.agent.verifact.verification.VerificationRecordRepository;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.ZoneOffset;
import java.util.Locale;
import java.util.UUID;

/** Collects "was this helpful?" feedback so wrong verdicts can be found and added to the eval set. */
@RestController
public class FeedbackController {

    static final int MAX_COMMENT_CHARS = 500;

    public record FeedbackRequest(Boolean helpful, String reason, String comment) {}

    private final VerificationFeedbackRepository feedbackRepository;
    private final VerificationRecordRepository verificationRepository;
    private final Clock clock;

    public FeedbackController(VerificationFeedbackRepository feedbackRepository,
                              VerificationRecordRepository verificationRepository, Clock clock) {
        this.feedbackRepository = feedbackRepository;
        this.verificationRepository = verificationRepository;
        this.clock = clock;
    }

    @PostMapping("/api/v2/verifications/{id}/feedback")
    public ResponseEntity<Void> submit(@PathVariable("id") String id, @RequestBody(required = false) FeedbackRequest request) {
        UUID reportId;
        try {
            reportId = UUID.fromString(id);
        } catch (IllegalArgumentException e) {
            throw notFound();
        }
        if (request == null || request.helpful() == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Please say whether the check was helpful.");
        }
        FeedbackReason reason = parseReason(request.reason());
        String comment = request.comment() == null ? null : request.comment().strip();
        if (comment != null && comment.isEmpty()) {
            comment = null;
        }
        if (comment != null && comment.length() > MAX_COMMENT_CHARS) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "Please keep the comment under " + MAX_COMMENT_CHARS + " characters.");
        }
        if (!verificationRepository.existsById(reportId)) {
            throw notFound();
        }
        feedbackRepository.save(new VerificationFeedback(UUID.randomUUID(), reportId, request.helpful(), reason,
                comment, clock.instant().atOffset(ZoneOffset.UTC)));
        return ResponseEntity.noContent().build();
    }

    private static FeedbackReason parseReason(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return FeedbackReason.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Unknown feedback reason.");
        }
    }

    private static ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "That report doesn't exist or is no longer available.");
    }
}
