package com.ai.agent.verifact.controller;

import com.ai.agent.verifact.service.AiService;
import com.ai.agent.verifact.service.ImageOcrService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.http.ResponseEntity;

import java.io.File;
import java.io.IOException;

@RestController
@RequestMapping("/api/v1")
public class AiController {

    private final AiService aiService;
    private final ImageOcrService imageOcrService;

    @Autowired
    public AiController(AiService aiService, ImageOcrService imageOcrService) {
        this.aiService = aiService;
        this.imageOcrService = imageOcrService;
    }

    // ==============================
    // TEXT-BASED FAKE NEWS CHECK
    // ==============================
    @RequestMapping(value = "/isFakeNews", method = {RequestMethod.POST, RequestMethod.GET})
    public String isFakeNews(
            @RequestBody(required = false) NewsRequest request,
            @RequestParam(value = "news", required = false) String newsParam) {

        String newsText = (request != null ? request.getNews() : newsParam);

        if (newsText == null || newsText.isEmpty()) {
            return "Please provide news text via POST JSON or GET parameter ?news=...";
        }

        try {
            String result = aiService.isFakeNews(newsText);

            if (result.contains("Web Search is not available")) {
                return "Analysis complete, but Web Search is currently unavailable. " +
                       "Check your Google API key and enable the Custom Search API.";
            }

            return result;
        } catch (Exception e) {
            return "Failed to analyze the news: " + e.getMessage();
        }
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
        if (file.isEmpty()) {
            return "No file uploaded.";
        }

        try {
            File tempFile = File.createTempFile("uploaded_", ".jpg");
            file.transferTo(tempFile);

            String extractedText = imageOcrService.extractTextFromImage(tempFile);
            return aiService.isFakeNews(extractedText);

        } catch (IOException e) {
            return "Failed to process the image: " + e.getMessage();
        }
    }

    // ==============================
    // AUDIO-BASED FAKE NEWS CHECK
    // ==============================
    @PostMapping("/analyzeAudio")
    public ResponseEntity<String> analyzeAudio(@RequestParam("file") MultipartFile file) {
        if (file.isEmpty()) {
            return ResponseEntity.badRequest().body("No audio file uploaded.");
        }

        try {
            byte[] audioBytes = file.getBytes();
            String result = aiService.isFakeNewsFromAudio(audioBytes);
            return ResponseEntity.ok(result);
        } catch (IOException e) {
            return ResponseEntity.status(500).body("Failed to process the audio: " + e.getMessage());
        }
    }
}
