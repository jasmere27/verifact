package com.ai.agent.verifact.service;

import com.ai.agent.verifact.common.ApiException;
import net.sourceforge.tess4j.Tesseract;
import net.sourceforge.tess4j.TesseractException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Iterator;

@Service
public class ImageOcrService {

    private static final Logger log = LoggerFactory.getLogger(ImageOcrService.class);

    /** Guards against decompression bombs: a small file can declare enormous dimensions. */
    static final long MAX_PIXELS = 40_000_000L;
    static final String UNSUPPORTED_MESSAGE = "Unsupported image. Upload a JPEG, PNG, GIF, BMP, or TIFF file.";

    private final String tessdataPath;

    public ImageOcrService(@Value("${tesseract.datapath}") String tessdataPath) {
        this.tessdataPath = tessdataPath;
    }

    /** Decodes the upload in memory (no temp files) and returns the text Tesseract finds in it. */
    public String extractText(byte[] imageBytes) {
        BufferedImage image = decode(imageBytes);

        Tesseract tesseract = new Tesseract();
        tesseract.setDatapath(tessdataPath);
        String text;
        try {
            text = tesseract.doOCR(image);
        } catch (TesseractException | UnsatisfiedLinkError | NoClassDefFoundError e) {
            // The two Errors mean the Tesseract native library isn't installed.
            log.error("OCR failed", e);
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Reading text from images is unavailable right now. Try pasting the text instead.", e);
        }

        if (text == null || text.isBlank()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "No readable text was found in that image.");
        }
        return text.trim();
    }

    /** Validates the bytes are a supported image of sane dimensions, then decodes it. */
    static BufferedImage decode(byte[] imageBytes) {
        try (ImageInputStream input = ImageIO.createImageInputStream(new ByteArrayInputStream(imageBytes))) {
            Iterator<ImageReader> readers = input == null ? null : ImageIO.getImageReaders(input);
            if (readers == null || !readers.hasNext()) {
                throw new ApiException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, UNSUPPORTED_MESSAGE);
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(input, true, true);
                long pixels = (long) reader.getWidth(0) * reader.getHeight(0);
                if (pixels > MAX_PIXELS) {
                    throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "That image's dimensions are too large.");
                }
                return reader.read(0);
            } finally {
                reader.dispose();
            }
        } catch (IOException | RuntimeException e) {
            if (e instanceof ApiException apiException) {
                throw apiException;
            }
            throw new ApiException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, UNSUPPORTED_MESSAGE, e);
        }
    }
}
