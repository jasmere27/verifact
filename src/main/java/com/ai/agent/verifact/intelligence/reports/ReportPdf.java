package com.ai.agent.verifact.intelligence.reports;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.springframework.stereotype.Component;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Renders a {@link Report} as an A4 PDF: headings, wrapped paragraphs, bullet lists, source links in full. */
@Component
public class ReportPdf {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH).withZone(ZoneOffset.UTC);
    private static final float MARGIN = 56;
    private static final Color INK = new Color(0x12, 0x18, 0x2b);
    private static final Color MUTED = new Color(0x58, 0x60, 0x79);
    private static final Color ACCENT = new Color(0x1d, 0x4e, 0xd8);

    public byte[] render(Report r) {
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Writer w = new Writer(doc);
            w.text(r.title(), Writer.BOLD, 17, INK, 0);
            w.gap(4);
            w.text(r.workspaceName() + " · " + DAY.format(r.periodStart()) + " – " + DAY.format(r.periodEnd()) + " · generated "
                    + DAY.format(r.generatedAt()) + (r.generatedBy() == null ? "" : " by " + r.generatedBy()), Writer.REGULAR, 9, MUTED, 0);
            w.gap(14);

            w.heading("Executive summary");
            r.executiveSummary().forEach(s -> w.paragraph(s));

            w.heading("Recommended actions");
            if (r.recommendedActions().isEmpty()) {
                w.paragraph("No actions triggered by the rules for this period.");
            }
            r.recommendedActions().forEach(w::bullet);

            w.heading("Monitoring coverage");
            w.paragraph(r.coverage().runs() + " runs (" + r.coverage().failedRuns() + " failed, " + r.coverage().partialRuns() + " partial).");
            for (Report.ProjectCoverage p : r.coverage().projects()) {
                w.bullet(p.name() + " (" + p.brand() + ", " + p.status().toLowerCase(Locale.ROOT) + "): " + String.join(", ", p.sources()) + "; "
                        + p.runs() + " runs, " + p.failedRuns() + " failed; last successful run "
                        + (p.lastSuccessAt() == null ? "never" : DAY.format(p.lastSuccessAt())) + ".");
            }

            w.heading("New mentions");
            w.paragraph(r.mentions().total() + " new mentions: " + counts(r.mentions().byRelevance()) + " relevance; "
                    + r.mentions().withClaims() + " with a potentially damaging claim identified by AI.");
            for (Report.MentionLine m : r.importantMentions()) {
                w.bullet((m.title() == null || m.title().isBlank() ? m.domain() : m.title()) + " (" + m.domain()
                        + (m.publishedDate() == null ? "" : ", " + m.publishedDate()) + ")"
                        + (m.aiClaim() == null ? "" : " — AI reading: " + m.aiClaim()));
                w.link(m.url());
            }

            w.heading("Incidents");
            w.paragraph(r.incidents().opened() + " opened, " + r.incidents().resolved() + " resolved, " + r.incidents().openNow()
                    + " open now (" + counts(r.incidents().openByPriority()) + ").");
            for (Report.IncidentLine i : r.openIncidents()) {
                w.bullet("[" + i.priority() + ", score " + i.riskScore() + "] " + i.title() + " — " + i.status().replace('_', ' ').toLowerCase(Locale.ROOT)
                        + (i.owner() == null ? ", unassigned" : ", owner " + i.owner()) + "; " + i.mentions() + " linked mention(s).");
            }

            w.heading("Investigation findings");
            if (r.findings().isEmpty()) {
                w.paragraph("No investigations completed in this period.");
            }
            for (Report.Finding f : r.findings()) {
                w.bullet(label(f.assessment()) + ": " + f.claim());
                if (f.excerpt() != null) {
                    w.text("\"" + f.excerpt() + "\" — " + f.citationDomain(), Writer.ITALIC, 9, MUTED, 22);
                    w.link(f.citationUrl());
                }
            }

            if (!r.themes().isEmpty()) {
                w.heading("Emerging themes");
                w.paragraph("Monitored keywords and topics most often matched by new mentions:");
                r.themes().forEach(t -> w.bullet(t.term() + ": " + t.mentions() + " mention(s)"));
            }
            if (!r.evidenceChanges().isEmpty()) {
                w.heading("Changes in available evidence");
                r.evidenceChanges().forEach(c -> w.bullet(c.investigation() + " v" + c.version() + " (" + DAY.format(c.at()) + "): " + c.summary()));
            }
            if (!r.unresolved().isEmpty()) {
                w.heading("Unresolved issues");
                r.unresolved().forEach(w::bullet);
            }
            w.heading("Source coverage and limitations");
            r.limitations().forEach(w::bullet);
            w.gap(10);
            w.text(r.disclaimer(), Writer.ITALIC, 8, MUTED, 0);
            w.close();
            doc.save(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String counts(Map<String, Integer> m) {
        return String.join(", ", m.entrySet().stream().map(e -> e.getValue() + " " + e.getKey().toLowerCase(Locale.ROOT).replace('_', ' ')).toList());
    }

    static String label(String assessment) {
        return switch (assessment) {
            case "SUPPORTED" -> "Supported by available evidence";
            case "CONTRADICTED" -> "Contradicted by available evidence";
            case "MIXED" -> "Mixed evidence";
            case "INSUFFICIENT_EVIDENCE" -> "Insufficient evidence";
            case "MISSING_CONTEXT" -> "Outdated or missing context";
            default -> "Under investigation";
        };
    }

    /** Minimal flowing-text writer over PDFBox pages. */
    static final class Writer {
        static final PDType1Font REGULAR = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
        static final PDType1Font BOLD = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
        static final PDType1Font ITALIC = new PDType1Font(Standard14Fonts.FontName.HELVETICA_OBLIQUE);

        private final PDDocument doc;
        private PDPageContentStream cs;
        private float y;
        private final float width = PDRectangle.A4.getWidth() - 2 * MARGIN;
        private int pageNo;

        Writer(PDDocument doc) throws IOException {
            this.doc = doc;
            newPage();
        }

        void heading(String s) {
            gap(10);
            text(s, BOLD, 12, ACCENT, 0);
            gap(3);
        }

        void paragraph(String s) {
            text(s, REGULAR, 10, INK, 0);
            gap(3);
        }

        void bullet(String s) {
            text("•  " + s, REGULAR, 10, INK, 10);
            gap(2);
        }

        void link(String url) {
            if (url != null) {
                text(url, REGULAR, 8, ACCENT, 22);
                gap(2);
            }
        }

        void gap(float g) {
            y -= g;
        }

        void text(String raw, PDType1Font font, float size, Color color, float indent) {
            try {
                float leading = size * 1.35f;
                for (String line : wrap(safe(raw), font, size, width - indent)) {
                    if (y - leading < MARGIN) {
                        newPage();
                    }
                    y -= leading;
                    cs.beginText();
                    cs.setFont(font, size);
                    cs.setNonStrokingColor(color);
                    cs.newLineAtOffset(MARGIN + indent, y);
                    cs.showText(line);
                    cs.endText();
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        private void newPage() throws IOException {
            if (cs != null) {
                cs.close();
            }
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);
            cs = new PDPageContentStream(doc, page);
            pageNo++;
            y = PDRectangle.A4.getHeight() - MARGIN;
            cs.beginText();
            cs.setFont(REGULAR, 7);
            cs.setNonStrokingColor(MUTED);
            cs.newLineAtOffset(MARGIN, MARGIN / 2);
            cs.showText("VeriFact Intelligence · page " + pageNo);
            cs.endText();
        }

        void close() throws IOException {
            cs.close();
        }

        static List<String> wrap(String text, PDType1Font font, float size, float max) throws IOException {
            List<String> lines = new ArrayList<>();
            for (String para : text.split("\n")) {
                StringBuilder line = new StringBuilder();
                for (String word : para.split(" ")) {
                    String candidate = line.isEmpty() ? word : line + " " + word;
                    if (font.getStringWidth(candidate) / 1000 * size <= max) {
                        line.setLength(0);
                        line.append(candidate);
                        continue;
                    }
                    if (!line.isEmpty()) {
                        lines.add(line.toString());
                        line.setLength(0);
                    }
                    // A single word (e.g. a long URL) wider than the line is split by characters.
                    String w = word;
                    while (font.getStringWidth(w) / 1000 * size > max && w.length() > 1) {
                        int cut = w.length();
                        while (cut > 1 && font.getStringWidth(w.substring(0, cut)) / 1000 * size > max) {
                            cut--;
                        }
                        lines.add(w.substring(0, cut));
                        w = w.substring(cut);
                    }
                    line.append(w);
                }
                lines.add(line.toString());
            }
            return lines;
        }

        /** The standard PDF fonts cover Latin-1 plus a few typographic marks; anything else becomes "?". */
        static String safe(String s) {
            if (s == null) {
                return "";
            }
            StringBuilder b = new StringBuilder();
            s.codePoints().forEach(cp -> {
                switch (cp) {
                    case '‘', '’' -> b.append('\'');
                    case '“', '”' -> b.append('"');
                    case '–', '—', '•', '…', '€' -> b.appendCodePoint(cp);
                    case '→' -> b.append("->");
                    case '\t' -> b.append(' ');
                    case '\n' -> b.append('\n');
                    default -> {
                        if ((cp >= 0x20 && cp < 0x7F) || (cp >= 0xA0 && cp <= 0xFF)) {
                            b.appendCodePoint(cp);
                        } else if (!Character.isISOControl(cp)) {
                            b.append('?');
                        }
                    }
                }
            });
            return b.toString();
        }
    }
}
