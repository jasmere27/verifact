package com.ai.agent.verifact.research;

import com.ai.agent.verifact.common.ApiException;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.io.MemoryUsageSetting;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.openxml4j.util.ZipSecureFile;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFShape;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xslf.usermodel.XSLFTextShape;
import org.apache.poi.xwpf.usermodel.IBodyElement;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Text from a student's uploaded draft: PDF (PDFBox), DOCX and PPTX (Apache POI), or plain text.
 * The type comes from the file's bytes, not its name. Everything happens in memory, with limits on
 * pages, characters, time, concurrency and zip expansion; the file itself is never stored.
 */
@Component
public class DocumentExtractor {

    private static final Logger log = LoggerFactory.getLogger(DocumentExtractor.class);

    public static final int MAX_BYTES = 10 * 1024 * 1024;
    public static final int MAX_PAGES = 60;
    public static final int MAX_SLIDES = 80;
    public static final int MAX_CHARS = 60_000;
    private static final long TIMEOUT_SECONDS = 25;

    public enum Kind { PDF, DOCX, PPTX, TXT }

    /** @param truncated true when the text was cut at {@link #MAX_CHARS} or the page/slide limit */
    public record Extracted(Kind kind, String text, int pages, boolean truncated) {}

    private static final Semaphore SLOTS = new Semaphore(2);
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    static {
        // Reject zip bombs in DOCX/PPTX: an entry may not expand to more than 100x its compressed size or 50 MB.
        ZipSecureFile.setMinInflateRatio(0.01);
        ZipSecureFile.setMaxEntrySize(50L * 1024 * 1024);
    }

    public Extracted extract(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "The file is empty.");
        }
        if (bytes.length > MAX_BYTES) {
            throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "The file is larger than 10 MB.");
        }
        Kind kind = detect(bytes);
        if (!SLOTS.tryAcquire()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "We're reading other documents right now. Please try again in a moment.");
        }
        try {
            Future<Extracted> f = executor.submit(() -> read(kind, bytes));
            Extracted e = f.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (e.text().strip().length() < 200) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, kind == Kind.PDF
                        ? "We couldn't find text in this PDF. If it's a scan, export it from Word or Google Docs as a PDF or DOCX instead."
                        : "We couldn't find enough text in this file to analyse.");
            }
            return e;
        } catch (TimeoutException e) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "This file took too long to read. Try a smaller file or export it as DOCX.");
        } catch (ExecutionException e) {
            if (e.getCause() instanceof ApiException api) {
                throw api;
            }
            log.info("Document extraction failed kind={} error={}", kind, e.getCause() == null ? "?" : e.getCause().getClass().getSimpleName());
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "We couldn't read this file. It may be damaged; try saving it again as PDF or DOCX.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "Reading the file was interrupted. Please try again.");
        } finally {
            SLOTS.release();
        }
    }

    static Kind detect(byte[] b) {
        if (startsWith(b, "%PDF-")) {
            return Kind.PDF;
        }
        if (b.length >= 4 && b[0] == 'P' && b[1] == 'K' && b[2] == 3 && b[3] == 4) {
            String type = officeType(b);
            if (type != null) {
                return type.equals("word") ? Kind.DOCX : Kind.PPTX;
            }
            throw unsupported();
        }
        if (b.length >= 4 && (b[0] & 0xFF) == 0xD0 && (b[1] & 0xFF) == 0xCF && (b[2] & 0xFF) == 0x11 && (b[3] & 0xFF) == 0xE0) {
            throw new ApiException(HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                    "Old Word/PowerPoint files (.doc, .ppt) aren't supported. Save it as .docx, .pptx or PDF and upload again.");
        }
        if (isText(b)) {
            return Kind.TXT;
        }
        throw unsupported();
    }

    private static ApiException unsupported() {
        return new ApiException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Upload a PDF, Word (.docx), PowerPoint (.pptx) or plain-text file.");
    }

    /** "word" or "ppt" from the zip's entry names (reads names only, never entry contents). */
    private static String officeType(byte[] b) {
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(b))) {
            ZipEntry e;
            int n = 0;
            while ((e = zip.getNextEntry()) != null && n++ < 2000) {
                String name = e.getName();
                if (name.equals("word/document.xml")) {
                    return "word";
                }
                if (name.equals("ppt/presentation.xml")) {
                    return "ppt";
                }
            }
        } catch (IOException | IllegalArgumentException e) {
            return null;
        }
        return null;
    }

    private static boolean isText(byte[] b) {
        for (int i = 0; i < Math.min(b.length, 8192); i++) {
            if (b[i] == 0) {
                return false;
            }
        }
        try {
            StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(b));
            return true;
        } catch (CharacterCodingException e) {
            return false;
        }
    }

    private static boolean startsWith(byte[] b, String prefix) {
        byte[] p = prefix.getBytes(StandardCharsets.US_ASCII);
        if (b.length < p.length) {
            return false;
        }
        for (int i = 0; i < p.length; i++) {
            if (b[i] != p[i]) {
                return false;
            }
        }
        return true;
    }

    private static Extracted read(Kind kind, byte[] bytes) throws IOException {
        return switch (kind) {
            case PDF -> pdf(bytes);
            case DOCX -> docx(bytes);
            case PPTX -> pptx(bytes);
            case TXT -> cap(Kind.TXT, new String(bytes, StandardCharsets.UTF_8), 0, false);
        };
    }

    private static Extracted pdf(byte[] bytes) throws IOException {
        try (PDDocument doc = Loader.loadPDF(bytes, "", null, null, MemoryUsageSetting.setupMainMemoryOnly(64L * 1024 * 1024).streamCache)) {
            int pages = doc.getNumberOfPages();
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            stripper.setStartPage(1);
            stripper.setEndPage(Math.min(pages, MAX_PAGES));
            return cap(Kind.PDF, stripper.getText(doc), pages, pages > MAX_PAGES);
        } catch (InvalidPasswordException e) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "This PDF is password-protected. Remove the password and upload it again.");
        }
    }

    private static Extracted docx(byte[] bytes) throws IOException {
        try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(bytes))) {
            StringBuilder sb = new StringBuilder();
            for (IBodyElement el : doc.getBodyElements()) {
                if (sb.length() > MAX_CHARS) {
                    break;
                }
                if (el instanceof XWPFParagraph p) {
                    sb.append(p.getText()).append('\n');
                } else if (el instanceof XWPFTable t) {
                    sb.append(t.getText()).append('\n');
                }
            }
            return cap(Kind.DOCX, sb.toString(), 0, false);
        }
    }

    private static Extracted pptx(byte[] bytes) throws IOException {
        try (XMLSlideShow show = new XMLSlideShow(new ByteArrayInputStream(bytes))) {
            StringBuilder sb = new StringBuilder();
            int slides = show.getSlides().size();
            int n = 0;
            for (XSLFSlide slide : show.getSlides()) {
                if (++n > MAX_SLIDES || sb.length() > MAX_CHARS) {
                    break;
                }
                for (XSLFShape shape : slide.getShapes()) {
                    if (shape instanceof XSLFTextShape t) {
                        sb.append(t.getText()).append('\n');
                    }
                }
                sb.append('\n');
            }
            return cap(Kind.PPTX, sb.toString(), slides, slides > MAX_SLIDES);
        }
    }

    private static Extracted cap(Kind kind, String raw, int pages, boolean cut) {
        String text = raw.replace("\r\n", "\n").replace('\r', '\n')
                .replaceAll("[\\u0000-\\u0008\\u000B\\u000C\\u000E-\\u001F]", "")
                .replaceAll("[ \\t\\u00A0]+", " ")
                .replaceAll("\\n{3,}", "\n\n")
                .strip();
        boolean truncated = cut || text.length() > MAX_CHARS;
        return new Extracted(kind, text.length() > MAX_CHARS ? text.substring(0, MAX_CHARS) : text, pages, truncated);
    }
}
