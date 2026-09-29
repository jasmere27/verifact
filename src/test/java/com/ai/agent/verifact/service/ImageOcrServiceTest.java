package com.ai.agent.verifact.service;

import com.ai.agent.verifact.common.ApiException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Covers upload validation only; OCR itself needs the Tesseract native library. */
class ImageOcrServiceTest {

    @Test
    void decodesSupportedImages() throws IOException {
        assertThat(ImageOcrService.decode(png(20, 10)).getWidth()).isEqualTo(20);
    }

    @Test
    void rejectsNonImages() {
        assertThatThrownBy(() -> ImageOcrService.decode("<script>alert(1)</script>".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE));
    }

    @Test
    void rejectsTruncatedImages() throws IOException {
        byte[] full = png(20, 10);
        byte[] truncated = java.util.Arrays.copyOf(full, 30);
        assertThatThrownBy(() -> ImageOcrService.decode(truncated)).isInstanceOf(ApiException.class);
    }

    @Test
    void rejectsDecompressionBombsBeforeDecoding() throws IOException {
        byte[] bomb = withDimensions(png(1, 1), 50_000, 50_000);
        assertThatThrownBy(() -> ImageOcrService.decode(bomb))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE));
    }

    private static byte[] png(int width, int height) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB), "png", out);
        return out.toByteArray();
    }

    /** Rewrites the PNG IHDR width/height (and its CRC) to claim a much larger image. */
    private static byte[] withDimensions(byte[] png, int width, int height) {
        byte[] copy = png.clone();
        ByteBuffer buffer = ByteBuffer.wrap(copy);
        buffer.putInt(16, width);
        buffer.putInt(20, height);
        CRC32 crc = new CRC32();
        crc.update(copy, 12, 17); // "IHDR" + 13 data bytes
        buffer.putInt(29, (int) crc.getValue());
        return copy;
    }
}
