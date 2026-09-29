package com.ai.agent.verifact.controller;

import com.ai.agent.verifact.common.ApiException;
import com.ai.agent.verifact.model.InputType;
import com.ai.agent.verifact.service.AiService;
import com.ai.agent.verifact.service.ImageOcrService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

/**
 * Verification endpoints. Success responses are plain-text reports (unchanged contract);
 * errors are RFC 9457 problem+json with a non-2xx status.
 */
@RestController
@RequestMapping("/api/v1")
public class AiController {

    private final AiService aiService;
    private final ImageOcrService imageOcrService;
    private final int maxInputChars;

    public AiController(AiService aiService,
                        ImageOcrService imageOcrService,
                        @Value("${app.input.max-chars:10000}") int maxInputChars) {
        this.aiService = aiService;
        this.imageOcrService = imageOcrService;
        this.maxInputChars = maxInputChars;
    }

    // ==============================
    // TEXT-BASED FAKE NEWS CHECK
    // ==============================
    @RequestMapping(value = "/isFakeNews", method = {RequestMethod.POST, RequestMethod.GET})
    public String isFakeNews(
            @RequestBody(required = false) NewsRequest request,
            @RequestParam(value = "news", required = false) String newsParam) {

        String newsText = (request != null && request.getNews() != null ? request.getNews() : newsParam);

        if (newsText == null || newsText.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "Please provide news text via POST JSON {\"news\": \"...\"} or GET parameter ?news=...");
        }
        if (newsText.length() > maxInputChars) {
            throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE,
                    "That text is too long. Please submit at most " + maxInputChars + " characters.");
        }

        return aiService.isFakeNews(newsText);
    }

    // DTO for POST requests
    public static class NewsRequest {
        private String news;
        public String getNews() { return news; }
        public void setNews(String news) { this.news = news; }
    }

    // ==============================
    // IMAGE-BASED FAKE NEWS CHECK
    // ==============================
    @PostMapping("/analyzeImage")
    public String analyzeImage(@RequestParam("file") MultipartFile file) {
        String extractedText = imageOcrService.extractText(readUpload(file, "No image uploaded."));
        return aiService.isFakeNews(extractedText, InputType.IMAGE);
    }

    // ==============================
    // AUDIO-BASED FAKE NEWS CHECK
    // ==============================
    @PostMapping("/analyzeAudio")
    public String analyzeAudio(@RequestParam("file") MultipartFile file) {
        return aiService.isFakeNewsFromAudio(readUpload(file, "No audio file uploaded."));
    }

    private static byte[] readUpload(MultipartFile file, String emptyMessage) {
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
