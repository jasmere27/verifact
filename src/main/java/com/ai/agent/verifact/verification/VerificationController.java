package com.ai.agent.verifact.verification;

import com.ai.agent.verifact.common.ApiException;
import com.ai.agent.verifact.service.ImageOcrService;
import com.ai.agent.verifact.tool.VoiceToTextTool;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.UUID;

/**
 * API v2: structured, evidence-cited verification reports. Reports get unguessable IDs and can be
 * re-opened (and shared) with GET by id.
 */
@RestController
@RequestMapping("/api/v2/verifications")
public class VerificationController {

    public record VerifyRequest(String input) {}

    private final VerificationService verificationService;
    private final VerificationStore store;
    private final ImageOcrService imageOcrService;
    private final VoiceToTextTool voiceToText;
    private final int maxInputChars;

    public VerificationController(VerificationService verificationService, VerificationStore store,
                                  ImageOcrService imageOcrService, VoiceToTextTool voiceToText,
                                  @Value("${app.input.max-chars:10000}") int maxInputChars) {
        this.verificationService = verificationService;
        this.store = store;
        this.imageOcrService = imageOcrService;
        this.voiceToText = voiceToText;
        this.maxInputChars = maxInputChars;
    }

    @PostMapping
    public VerificationResult verify(@RequestBody(required = false) VerifyRequest request) {
        String input = request == null ? null : request.input();
        if (input == null || input.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Please provide a claim, article text, or link to check.");
        }
        if (input.length() > maxInputChars) {
            throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE,
                    "That text is too long. Please submit at most " + maxInputChars + " characters.");
        }
        return verificationService.verifyText(input);
    }

    @PostMapping("/image")
    public VerificationResult verifyImage(@RequestParam("file") MultipartFile file) {
        String text = imageOcrService.extractText(read(file, "No image uploaded."));
        return verificationService.verifyImageText(file.getOriginalFilename(), text);
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
