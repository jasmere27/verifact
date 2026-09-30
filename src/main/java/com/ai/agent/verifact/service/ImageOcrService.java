package com.ai.agent.verifact.service;

import com.ai.agent.verifact.ai.ImageInput;
import com.ai.agent.verifact.common.ApiException;
import net.sourceforge.tess4j.Tesseract;
import net.sourceforge.tess4j.TesseractException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Iterator;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

@Service
public class ImageOcrService {

    private static final Logger log = LoggerFactory.getLogger(ImageOcrService.class);

    /**
     * Guards against decompression bombs: a small file can declare enormous dimensions.
     * 16 MP decodes to ~64 MB, which matters on a 512 MB instance.
     */
    static final long MAX_PIXELS = 16_000_000L;

    /** Decoded images (for OCR or vision) are memory-heavy; cap how many are processed at once. */
    private final Semaphore ocrSlots = new Semaphore(2);
    /** Longest side sent to a vision model; providers downscale beyond this anyway, so more only costs upload time. */
    static final int MAX_VISION_SIDE = 2048;

    static final String UNSUPPORTED_MESSAGE = "Unsupported image. Upload a JPEG, PNG, GIF, BMP, or TIFF file.";

    private final String tessdataPath;

    public ImageOcrService(@Value("${tesseract.datapath}") String tessdataPath) {
        this.tessdataPath = tessdataPath;
    }

    /** Decodes the upload in memory (no temp files) and returns the text Tesseract finds in it. */
    public String extractText(byte[] imageBytes) {
        acquireSlot();
        try {
            return ocr(decode(imageBytes));
        } finally {
            ocrSlots.release();
        }
    }

    /**
     * Prepares an upload for a vision model: validates and decodes it like OCR does, scales it down to
     * at most {@value #MAX_VISION_SIDE}px on the longest side, and re-encodes it as JPEG. Re-encoding
     * means the model only ever sees plain pixels in a format it accepts (BMP/TIFF become JPEG, only
     * the first frame of a GIF is kept) and drops metadata such as GPS location.
     */
    public ImageInput prepareForVision(byte[] imageBytes) {
        acquireSlot();
        try {
            return new ImageInput(toJpeg(scaleDown(decode(imageBytes), MAX_VISION_SIDE)), "image/jpeg");
        } finally {
            ocrSlots.release();
        }
    }

    private void acquireSlot() {
        boolean acquired;
        try {
            acquired = ocrSlots.tryAcquire(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            acquired = false;
        }
        if (!acquired) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "VeriFact is busy reading other images. Please try again.");
        }
    }

    /** Draws the image onto an opaque RGB canvas (JPEG has no transparency), shrinking it if needed. */
    static BufferedImage scaleDown(BufferedImage image, int maxSide) {
        int width = image.getWidth();
        int height = image.getHeight();
        double scale = Math.min(1.0, (double) maxSide / Math.max(width, height));
        int targetWidth = Math.max(1, (int) Math.round(width * scale));
        int targetHeight = Math.max(1, (int) Math.round(height * scale));
        BufferedImage out = new BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, targetWidth, targetHeight);
            g.drawImage(image, 0, 0, targetWidth, targetHeight, null);
        } finally {
            g.dispose();
        }
        return out;
    }

    private static byte[] toJpeg(BufferedImage image) {
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ImageOutputStream output = ImageIO.createImageOutputStream(bytes)) {
            ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(0.88f); // small text in screenshots stays sharp
            writer.setOutput(output);
            writer.write(null, new IIOImage(image, null, null), param);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            writer.dispose();
        }
        return bytes.toByteArray();
    }

    private String ocr(BufferedImage image) {
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
