package com.ai.agent.verifact.research;

import com.ai.agent.verifact.ai.LlmClient;
import com.ai.agent.verifact.evidence.Grounding;
import com.ai.agent.verifact.research.Draft.Statement;
import com.ai.agent.verifact.research.StudentOutputs.DraftReading;
import com.ai.agent.verifact.research.StudentOutputs.Uncited;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads a student's draft: one model pass for a summary, key concepts and uncited claims (all checked
 * against the draft's own words in code), plus a reference-list split and an excerpt sized for the
 * existing citation check. No sources are suggested here: the workspace's "find sources for this"
 * search does that from the index.
 */
@Service
public class DraftAnalysisService {

    private static final Logger log = LoggerFactory.getLogger(DraftAnalysisService.class);

    static final int MAX_MODEL_CHARS = 30_000;
    /** The citation check accepts 10,000 characters. */
    static final int CHECK_CHARS = 10_000;
    static final int MAX_REFERENCE_CHARS = 5_000;

    private static final Pattern REFERENCES_HEADING = Pattern.compile(
            "(?im)^\\s*(?:[ivx\\d.]+\\s*)?(references|reference list|bibliography|works cited|literature cited)\\s*:?\\s*$");
    private static final Pattern APPENDIX_HEADING = Pattern.compile("(?im)^\\s*(appendix|appendices)\\b.*$");
    private static final Pattern APA_YEAR = Pattern.compile("\\((?:19|20)\\d{2}[a-z]?(?:, [A-Z][a-z]+(?: \\d{1,2})?)?\\)\\.");
    private static final Pattern NUMBERED = Pattern.compile("(?m)^\\s*\\[?\\d{1,3}[\\].]\\s+\\S");
    private static final Pattern IN_TEXT = Pattern.compile(
            "\\((?:[^()]{0,120}?[A-Z][^()]{0,120}?,?\\s(?:19|20)\\d{2}[a-z]?(?:[;,][^()]{0,120})?)\\)|\\[\\d{1,3}(?:\\s*[,–-]\\s*\\d{1,3})*\\]");
    private static final Pattern SENTENCE_END = Pattern.compile("(?<=[.!?])\\s+(?=[\"“(\\[]?[A-Z0-9])");

    private final LlmClient llm;
    private final Clock clock;

    public DraftAnalysisService(LlmClient llm, Clock clock) {
        this.llm = llm;
        this.clock = clock;
    }

    public Draft analyze(String fileName, DocumentExtractor.Extracted doc) {
        String text = doc.text();
        List<String> limitations = new ArrayList<>();
        if (doc.truncated()) {
            limitations.add("Only the start of the file was read (limits: " + String.format(Locale.ROOT, "%,d", DocumentExtractor.MAX_CHARS)
                    + " characters, " + DocumentExtractor.MAX_PAGES + " PDF pages, " + DocumentExtractor.MAX_SLIDES + " slides).");
        }

        String[] parts = split(text);
        String body = parts[0];
        String references = parts[1];
        int entries = references == null ? 0 : referenceEntries(references);
        int inText = count(IN_TEXT, body);
        if (references == null) {
            limitations.add("No reference list was found (a heading like \"References\" or \"Bibliography\"), so references can't be checked.");
        }

        // Model pass on the body only (the reference list adds nothing to a summary).
        String forModel = body.length() > MAX_MODEL_CHARS ? body.substring(0, MAX_MODEL_CHARS) : body;
        if (body.length() > MAX_MODEL_CHARS) {
            limitations.add("The summary and uncited-claim check read the first " + String.format(Locale.ROOT, "%,d", MAX_MODEL_CHARS) + " characters.");
        }
        String nonce = UUID.randomUUID().toString().replace("-", "");
        DraftReading reading = llm.generateQuick(StudentPrompts.withNonce(StudentPrompts.DRAFT_SYSTEM, nonce),
                "<<<DRAFT_" + nonce + ">>>\n" + forModel + "\n<<<END_DRAFT_" + nonce + ">>>", DraftReading.class);

        String material = Grounding.material(forModel);
        String summary = reading == null ? "" : Grounding.supportedSentences(cap(reading.summary(), 1200), material);
        List<String> concepts = concepts(reading, material);
        List<Statement> statements = statements(reading, forModel);

        String checkText = references == null ? null : checkText(body, references);
        log.info("Draft analysed kind={} chars={} referenceEntries={} inText={} concepts={} uncited={}",
                doc.kind(), text.length(), entries, inText, concepts.size(), statements.size());
        return new Draft(cap(fileName, 200), doc.kind(), doc.pages(), text.length(), doc.truncated(), clock.instant(), text,
                summary.isBlank() ? null : summary, concepts, statements, entries, inText, checkText, limitations);
    }

    /** [body, references or null]: the text after the last "References"-style heading, up to any appendix. */
    static String[] split(String text) {
        Matcher m = REFERENCES_HEADING.matcher(text);
        int start = -1;
        int headingStart = -1;
        while (m.find()) {
            // The last heading wins, but not one in the first fifth (a table of contents lists "References" too).
            if (m.start() > text.length() / 5) {
                start = m.end();
                headingStart = m.start();
            }
        }
        if (start < 0) {
            return new String[]{text, null};
        }
        String refs = text.substring(start);
        Matcher appendix = APPENDIX_HEADING.matcher(refs);
        if (appendix.find()) {
            refs = refs.substring(0, appendix.start());
        }
        refs = refs.strip();
        return new String[]{text.substring(0, headingStart).strip(), refs.isEmpty() ? null : refs};
    }

    static int referenceEntries(String references) {
        int apa = count(APA_YEAR, references);
        return apa > 0 ? apa : count(NUMBERED, references);
    }

    /** Sentences with in-text citations (in order) plus the reference list, within the citation check's limit. */
    static String checkText(String body, String references) {
        String refs = references.length() > MAX_REFERENCE_CHARS ? cutAtLine(references, MAX_REFERENCE_CHARS) : references;
        int budget = CHECK_CHARS - refs.length() - "\n\nReferences\n".length();
        StringBuilder cited = new StringBuilder();
        for (String sentence : SENTENCE_END.split(body.replaceAll("\\s*\\n\\s*", " "))) {
            if (IN_TEXT.matcher(sentence).find()) {
                if (cited.length() + sentence.length() + 1 > budget) {
                    break;
                }
                cited.append(sentence.strip()).append(' ');
            }
        }
        return (cited.toString().strip() + "\n\nReferences\n" + refs).strip();
    }

    private static List<String> concepts(DraftReading reading, String material) {
        Set<String> out = new LinkedHashSet<>();
        if (reading != null && reading.concepts() != null) {
            for (String c : reading.concepts()) {
                String v = cap(c, 80);
                // Named as the draft names it: every word must be in the draft.
                if (v != null && !Grounding.words(v).isEmpty() && material.contains(" " + Grounding.words(v) + " ")) {
                    out.add(v);
                }
                if (out.size() == 10) {
                    break;
                }
            }
        }
        return List.copyOf(out);
    }

    private static List<Statement> statements(DraftReading reading, String draft) {
        List<Statement> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        if (reading == null || reading.statements() == null) {
            return out;
        }
        for (Uncited u : reading.statements()) {
            if (u == null) {
                continue;
            }
            String span = Grounding.findSpan(u.quote(), draft, 6);
            if (span == null || span.length() > 600 || IN_TEXT.matcher(span).find() || !seen.add(Grounding.words(span))) {
                continue;
            }
            // The model may drop a citation at the sentence's end; check the draft right after the span.
            int at = draft.replaceAll("\\s+", " ").indexOf(span);
            if (at >= 0) {
                String flat = draft.replaceAll("\\s+", " ");
                String after = flat.substring(at + span.length(), Math.min(flat.length(), at + span.length() + 60));
                if (IN_TEXT.matcher(after.split("(?<=[.!?])\\s", 2)[0]).find()) {
                    continue;
                }
            }
            out.add(new Statement(span, cap(u.why(), 160)));
            if (out.size() == 8) {
                break;
            }
        }
        return out;
    }

    private static int count(Pattern p, String s) {
        Matcher m = p.matcher(s);
        int n = 0;
        while (m.find()) {
            n++;
        }
        return n;
    }

    private static String cutAtLine(String s, int max) {
        int cut = s.lastIndexOf('\n', max);
        return s.substring(0, cut > max / 2 ? cut : max).strip();
    }

    private static String cap(String s, int max) {
        if (s == null || s.isBlank()) {
            return null;
        }
        String t = s.strip().replaceAll("\\s+", " ");
        return t.length() > max ? t.substring(0, max) : t;
    }
}
