
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

    // Endpoint for text-based fake news check
    @GetMapping("/isFakeNews")
    public String isFakeNews(@RequestParam String news) {
        return aiService.isFakeNews(news);
    }

    // Endpoint for image-based fake news check
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

    // Endpoint for audio-based fake news check
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