package com.ai.agent.verifact.verification;

import com.ai.agent.verifact.account.SignedInUser;
import com.ai.agent.verifact.ai.ImageInput;
import com.ai.agent.verifact.common.ApiException;
import com.ai.agent.verifact.service.ImageOcrService;
import com.ai.agent.verifact.tool.VoiceToTextTool;
import org.springframework.beans.factory.annotation.Value;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * API v2: structured, evidence-cited verification reports. Reports get unguessable IDs and can be
 * re-opened (and shared) with GET by id. A check may send {@code X-Edit-Token} (a random token the
 * browser keeps); the browser that created a report can then DELETE it with the same header. Signed in
 * (a bearer token), the check is added to the account's history and a report it creates belongs to the
 * account, which can delete it from any device (ADR-20).
 */
@RestController
@RequestMapping("/api/v2/verifications")
public class VerificationController {

    /** @param refresh true to skip reusing a recent report for the same input */
    public record VerifyRequest(String input, Boolean refresh) {

        boolean forceRefresh() {
            return Boolean.TRUE.equals(refresh);
        }
    }

    private final VerificationService verificationService;
    private final VerificationStreamer streamer;
    private final VerificationStore store;
    private final AccountChecks accountChecks;
    private final ImageOcrService imageOcrService;
    private final VoiceToTextTool voiceToText;
    private final int maxInputChars;
    private final boolean visionEnabled;
    private final Clock clock;

    /** Browser-generated edit tokens: base64url, at least 192 bits. Anything else is ignored. */
    private static final Pattern EDIT_TOKEN = Pattern.compile("[A-Za-z0-9_-]{32,128}");
    private static final String EDIT_TOKEN_HEADER = "X-Edit-Token";

    public VerificationController(VerificationService verificationService, VerificationStreamer streamer,
                                  VerificationStore store, AccountChecks accountChecks,
                                  ImageOcrService imageOcrService, VoiceToTextTool voiceToText,
                                  @Value("${app.input.max-chars:10000}") int maxInputChars,
                                  @Value("${app.vision.enabled:true}") boolean visionEnabled, Clock clock) {
        this.verificationService = verificationService;
        this.streamer = streamer;
        this.store = store;
        this.accountChecks = accountChecks;
        this.imageOcrService = imageOcrService;
        this.voiceToText = voiceToText;
        this.maxInputChars = maxInputChars;
        this.visionEnabled = visionEnabled;
        this.clock = clock;
    }

    @PostMapping
    public VerificationResult verify(@RequestBody(required = false) VerifyRequest request,
                                     @RequestHeader(value = EDIT_TOKEN_HEADER, required = false) String token,
                                     @AuthenticationPrincipal Jwt jwt) {
        String input = validInput(request);
        return owned(token, jwt, () -> verificationService.verifyText(input, VerificationProgress.NONE, request.forceRefresh()));
    }

    // Streaming variants: validation errors are ordinary problem+json responses; once the stream
    // starts, progress, the result, or an error arrive as Server-Sent Events.

    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter verifyStream(@RequestBody(required = false) VerifyRequest request, HttpServletResponse response,
                                   @RequestHeader(value = EDIT_TOKEN_HEADER, required = false) String token,
                                   @AuthenticationPrincipal Jwt jwt) {
        String input = validInput(request);
        noProxyBuffering(response);
        boolean refresh = request.forceRefresh();
        return streamer.start(progress -> owned(token, jwt, () -> verificationService.verifyText(input, progress, refresh)));
    }

    @PostMapping(value = "/image/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter verifyImageStream(@RequestParam("file") MultipartFile file, HttpServletResponse response,
                                        @RequestHeader(value = EDIT_TOKEN_HEADER, required = false) String token,
                                        @AuthenticationPrincipal Jwt jwt) {
        byte[] bytes = read(file, "No image uploaded.");
        String name = file.getOriginalFilename();
        noProxyBuffering(response);
        return streamer.start(progress -> {
            progress.stage(VerificationProgress.Stage.READING_INPUT);
            return owned(token, jwt, () -> verifyImage(name, bytes, progress));
        });
    }

    @PostMapping(value = "/audio/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter verifyAudioStream(@RequestParam("file") MultipartFile file, HttpServletResponse response,
                                        @RequestHeader(value = EDIT_TOKEN_HEADER, required = false) String token,
                                        @AuthenticationPrincipal Jwt jwt) {
        byte[] bytes = read(file, "No audio file uploaded.");
        String name = file.getOriginalFilename();
        noProxyBuffering(response);
        return streamer.start(progress -> {
            progress.stage(VerificationProgress.Stage.READING_INPUT);
            return owned(token, jwt, () -> verificationService.verifyAudioTranscript(name, voiceToText.transcribe(bytes), progress));
        });
    }

    /**
     * Runs a check. A report it creates (not one it reuses) becomes deletable with {@code token} and, when
     * signed in, by the account; signed in, the report is also added to the account's history.
     */
    private VerificationResult owned(String token, Jwt jwt, Supplier<VerificationResult> check) {
        Instant startedAt = clock.instant();
        VerificationResult result = check.get();
        SignedInUser user = jwt == null ? null : SignedInUser.from(jwt);
        boolean recorded = user != null && accountChecks.record(user, result);
        String editToken = token != null && EDIT_TOKEN.matcher(token).matches() ? token : null;
        store.attachCreator(result.id(), editToken, recorded ? user.id() : null, startedAt);
        return result;
    }

    private String validInput(VerifyRequest request) {
        String input = request == null ? null : request.input();
        if (input == null || input.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Please provide a claim, article text, or link to check.");
        }
        if (input.length() > maxInputChars) {
            throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE,
                    "That text is too long. Please submit at most " + maxInputChars + " characters.");
        }
        return input;
    }

    /** Asks reverse proxies not to buffer the event stream, so progress arrives as it happens. */
    private static void noProxyBuffering(HttpServletResponse response) {
        response.setHeader("X-Accel-Buffering", "no");
        response.setHeader("Cache-Control", "no-cache");
    }

    @PostMapping("/image")
    public VerificationResult verifyImage(@RequestParam("file") MultipartFile file) {
        return verifyImage(file.getOriginalFilename(), read(file, "No image uploaded."), VerificationProgress.NONE);
    }

    /** Vision model first (when enabled), OCR as the fallback; see {@link VerificationService#verifyImage}. */
    private VerificationResult verifyImage(String fileName, byte[] bytes, VerificationProgress progress) {
        ImageInput image = visionEnabled ? imageOcrService.prepareForVision(bytes) : null;
        return verificationService.verifyImage(fileName, image, () -> imageOcrService.extractText(bytes), progress);
    }

    @PostMapping("/audio")
    public VerificationResult verifyAudio(@RequestParam("file") MultipartFile file) {
        String transcript = voiceToText.transcribe(read(file, "No audio file uploaded."));
        return verificationService.verifyAudioTranscript(file.getOriginalFilename(), transcript);
    }

    @GetMapping("/{id}")
    public VerificationResult get(@PathVariable("id") String id) {
        UUID uuid;
        try {
            uuid = UUID.fromString(id);
        } catch (IllegalArgumentException e) {
            throw notFound();
        }
        return store.find(uuid).orElseThrow(VerificationController::notFound);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable("id") String id,
                                       @RequestHeader(value = EDIT_TOKEN_HEADER, required = false) String token,
                                       @AuthenticationPrincipal Jwt jwt) {
        UUID uuid;
        try {
            uuid = UUID.fromString(id);
        } catch (IllegalArgumentException e) {
            throw notFound();
        }
        store.delete(uuid, token, jwt == null ? null : SignedInUser.from(jwt).id());
        return ResponseEntity.noContent().build();
    }

    private static ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "That report doesn't exist or is no longer available.");
    }

    private static byte[] read(MultipartFile file, String emptyMessage) {
        if (file == null || file.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, emptyMessage);
        }
        try {
            return file.getBytes();
        } catch (IOException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "The upload could not be read. Please try again.", e);
        }
    }
}
