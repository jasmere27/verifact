package com.ai.agent.verifact.service;

import com.ai.agent.verifact.ai.ImageInput;
import com.ai.agent.verifact.common.ApiException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Covers upload validation and vision preparation; OCR itself needs the Tesseract native library. */
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
        byte[] bomb = withDimensions(png(1, 1), 5_000, 4_000);
        assertThatThrownBy(() -> ImageOcrService.decode(bomb))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE));
    }

    // ---- preparing uploads for the vision model ----

    private final ImageOcrService service = new ImageOcrService("/unused");

    @Test
    void largeImagesAreScaledDownKeepingTheirShapeAndSentAsJpeg() throws IOException {
        ImageInput prepared = service.prepareForVision(png(4000, 1000));

        assertThat(prepared.mimeType()).isEqualTo("image/jpeg");
        BufferedImage sent = ImageIO.read(new ByteArrayInputStream(prepared.bytes()));
        assertThat(sent.getWidth()).isEqualTo(ImageOcrService.MAX_VISION_SIDE);
        assertThat(sent.getHeight()).isEqualTo(512);
    }

    @Test
    void smallImagesKeepTheirSize() throws IOException {
        BufferedImage sent = ImageIO.read(new ByteArrayInputStream(service.prepareForVision(png(300, 200)).bytes()));
        assertThat(sent.getWidth()).isEqualTo(300);
        assertThat(sent.getHeight()).isEqualTo(200);
    }

    @Test
    void formatsTheModelCantReadAreConvertedAndTransparencyIsFlattened() throws IOException {
        ByteArrayOutputStream bmp = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(40, 30, BufferedImage.TYPE_INT_RGB), "bmp", bmp);
        assertThat(service.prepareForVision(bmp.toByteArray()).mimeType()).isEqualTo("image/jpeg");

        ByteArrayOutputStream transparent = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(40, 30, BufferedImage.TYPE_INT_ARGB), "png", transparent);
        BufferedImage sent = ImageIO.read(new ByteArrayInputStream(service.prepareForVision(transparent.toByteArray()).bytes()));
        assertThat(sent.getRGB(5, 5) & 0xFFFFFF).isGreaterThan(0xF0F0F0); // white, not black
    }

    @Test
    void metadataIsNotPassedOn() throws IOException {
        byte[] withText = withTextChunk(png(20, 10), "GPS 48.8584 2.2945");
        byte[] sent = service.prepareForVision(withText).bytes();
        assertThat(new String(sent, StandardCharsets.ISO_8859_1)).doesNotContain("48.8584");
    }

    @Test
    void visionPreparationRejectsTheSameBadUploadsAsOcr() throws IOException {
        assertThatThrownBy(() -> service.prepareForVision("not an image".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE));
        byte[] bomb = withDimensions(png(1, 1), 5_000, 4_000);
        assertThatThrownBy(() -> service.prepareForVision(bomb))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE));
    }

    /** Inserts a tEXt chunk right after IHDR. */
    private static byte[] withTextChunk(byte[] png, String text) {
        byte[] data = ("Comment\0" + text).getBytes(StandardCharsets.ISO_8859_1);
        ByteBuffer chunk = ByteBuffer.allocate(12 + data.length);
        chunk.putInt(data.length).put("tEXt".getBytes(StandardCharsets.ISO_8859_1)).put(data);
        CRC32 crc = new CRC32();
        crc.update(chunk.array(), 4, 4 + data.length);
        chunk.putInt((int) crc.getValue());
        int afterIhdr = 8 + 25;
        byte[] out = new byte[png.length + chunk.capacity()];
        System.arraycopy(png, 0, out, 0, afterIhdr);
        System.arraycopy(chunk.array(), 0, out, afterIhdr, chunk.capacity());
        System.arraycopy(png, afterIhdr, out, afterIhdr + chunk.capacity(), png.length - afterIhdr);
        return out;
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
