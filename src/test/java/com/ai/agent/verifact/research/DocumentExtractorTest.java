package com.ai.agent.verifact.research;

import com.ai.agent.verifact.common.ApiException;
import com.ai.agent.verifact.research.DocumentExtractor.Kind;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFTextBox;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Real files built in memory with the same libraries; no fixtures on disk. */
class DocumentExtractorTest {

    static final String PARAGRAPH = "The flipped classroom model moves direct instruction outside class time so that lessons can focus on "
            + "problem solving. This study examines Grade 11 students in a public senior high school in Cebu City. ";

    private final DocumentExtractor extractor = new DocumentExtractor();

    @Test
    void readsDocx() throws Exception {
        XWPFDocument doc = new XWPFDocument();
        doc.createParagraph().createRun().setText("Chapter 1 Introduction");
        doc.createParagraph().createRun().setText(PARAGRAPH + PARAGRAPH);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        doc.write(out);

        DocumentExtractor.Extracted e = extractor.extract(out.toByteArray());

        assertThat(e.kind()).isEqualTo(Kind.DOCX);
        assertThat(e.text()).contains("Chapter 1 Introduction").contains("Grade 11 students");
        assertThat(e.truncated()).isFalse();
    }

    @Test
    void readsPptxSlides() throws Exception {
        XMLSlideShow show = new XMLSlideShow();
        for (int i = 0; i < 3; i++) {
            XSLFTextBox box = show.createSlide().createTextBox();
            box.setText("Slide " + i + ": " + PARAGRAPH);
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        show.write(out);

        DocumentExtractor.Extracted e = extractor.extract(out.toByteArray());

        assertThat(e.kind()).isEqualTo(Kind.PPTX);
        assertThat(e.pages()).isEqualTo(3);
        assertThat(e.text()).contains("Slide 2:");
    }

    @Test
    void readsPdfText() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 10);
                cs.newLineAtOffset(40, 700);
                for (String line : (PARAGRAPH + PARAGRAPH).split("(?<=\\. )")) {
                    cs.showText(line.strip());
                    cs.newLineAtOffset(0, -14);
                }
                cs.endText();
            }
            doc.save(out);
        }

        DocumentExtractor.Extracted e = extractor.extract(out.toByteArray());

        assertThat(e.kind()).isEqualTo(Kind.PDF);
        assertThat(e.pages()).isEqualTo(1);
        assertThat(e.text()).contains("flipped classroom model");
    }

    @Test
    void aPdfWithoutTextIsExplainedAsAScan() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (PDDocument doc = new PDDocument()) {
            doc.addPage(new PDPage());
            doc.save(out);
        }
        assertThatThrownBy(() -> extractor.extract(out.toByteArray()))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getMessage()).contains("scan"));
    }

    @Test
    void plainTextIsAcceptedAndCapped() {
        String big = PARAGRAPH.repeat(400);
        DocumentExtractor.Extracted e = extractor.extract(big.getBytes(StandardCharsets.UTF_8));

        assertThat(e.kind()).isEqualTo(Kind.TXT);
        assertThat(e.text()).hasSize(DocumentExtractor.MAX_CHARS);
        assertThat(e.truncated()).isTrue();
    }

    @Test
    void typeComesFromTheBytesAndOldOrUnknownFormatsAreRefused() throws Exception {
        byte[] ole = {(byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0, (byte) 0xA1, (byte) 0xB1, 0x1A, (byte) 0xE1, 0, 0};
        assertThatThrownBy(() -> extractor.extract(ole))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
                    assertThat(e.getMessage()).contains(".docx");
                });

        ByteArrayOutputStream zip = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(zip)) {
            z.putNextEntry(new ZipEntry("payload.exe"));
            z.write(new byte[]{1, 2, 3});
        }
        assertThatThrownBy(() -> extractor.extract(zip.toByteArray()))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE));

        byte[] png = {(byte) 0x89, 'P', 'N', 'G', 0, 0, 0, 0};
        assertThatThrownBy(() -> extractor.extract(png)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> extractor.extract(new byte[0])).isInstanceOf(ApiException.class);
    }

    @Test
    void aZipBombDisguisedAsDocxIsRejected() throws Exception {
        ByteArrayOutputStream zip = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(zip)) {
            z.putNextEntry(new ZipEntry("[Content_Types].xml"));
            z.write("<Types/>".getBytes(StandardCharsets.UTF_8));
            z.putNextEntry(new ZipEntry("word/document.xml"));
            byte[] zeros = new byte[1024 * 1024];
            for (int i = 0; i < 60; i++) {
                z.write(zeros); // 60 MB of zeros compresses to ~60 KB
            }
        }
        assertThatThrownBy(() -> extractor.extract(zip.toByteArray()))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY));
    }
}
