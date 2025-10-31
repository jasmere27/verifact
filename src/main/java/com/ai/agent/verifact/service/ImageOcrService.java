
package com.ai.agent.verifact.service;

import java.io.File;
import java.io.IOException;

import org.springframework.stereotype.Service;

import net.sourceforge.tess4j.Tesseract;
import net.sourceforge.tess4j.TesseractException;

@Service
public class ImageOcrService {

    // Method to extract text from an image
    public String extractTextFromImage(File imageFile) throws IOException {
        Tesseract tesseract = new Tesseract();
        tesseract.setDatapath("C:/Program Files/Tesseract-OCR/tessdata"); // Set the correct path to tessdata folder

        try {
            // Perform OCR and return the extracted text
            return tesseract.doOCR(imageFile);
        } catch (TesseractException e) {
            throw new IOException("OCR failed: " + e.getMessage());
        }
    }
}