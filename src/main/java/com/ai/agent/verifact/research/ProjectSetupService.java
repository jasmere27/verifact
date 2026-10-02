package com.ai.agent.verifact.research;

import com.ai.agent.verifact.ai.LlmClient;
import com.ai.agent.verifact.evidence.Grounding;
import com.ai.agent.verifact.research.StudentOutputs.SetupQuestion;
import com.ai.agent.verifact.research.StudentOutputs.SetupReading;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * "Start from your Chapter 1" (ADR-25): reads the title, research questions and stated hypotheses from a student's
 * proposal or chapter so the project is set up for them. Questions and expected answers are kept only as the
 * document's own words (found word for word); the student confirms or edits everything on the project's Home.
 */
@Service
public class ProjectSetupService {

    private static final Logger log = LoggerFactory.getLogger(ProjectSetupService.class);

    static final int MAX_MODEL_CHARS = 20_000;
    static final int MAX_QUESTIONS = 10;
    private static final Pattern CHAPTER_HEADING = Pattern.compile(
            "(?i)^\\s*(chapter\\b|the problem and its (background|setting)|introduction$|review of related|methodology$|research methodology)");
    private static final Pattern LEADING_NUMBER = Pattern.compile("^\\s*(?:[(\\[]?(?:\\d{1,2}|[a-z]|[ivx]{1,4})[.)\\]]\\s+)+", Pattern.CASE_INSENSITIVE);
    private static final Pattern NULL_HYPOTHESIS = Pattern.compile("(?i)\\b(no significant|not significant|ho\\s*[:\\d])");

    public enum TitleSource {
        /** Printed in the document. */
        DOCUMENT,
        /** Written by the AI from the document's terms; the student should check it. */
        SUGGESTED,
        /** Neither: the file name or a placeholder. */
        NONE
    }

    public record Setup(String title, TitleSource titleSource, String field, List<ResearchProjectStore.QuestionInput> questions) {}

    private final LlmClient llm;

    public ProjectSetupService(LlmClient llm) {
        this.llm = llm;
    }

    public Setup read(String fileName, DocumentExtractor.Extracted doc) {
        String text = doc.text();
        String forModel = text.length() > MAX_MODEL_CHARS ? text.substring(0, MAX_MODEL_CHARS) : text;
        String nonce = UUID.randomUUID().toString().replace("-", "");
        SetupReading r = llm.generateQuick(StudentPrompts.withNonce(StudentPrompts.SETUP_SYSTEM, nonce),
                "<<<DOC_" + nonce + ">>>\n" + forModel + "\n<<<END_DOC_" + nonce + ">>>", SetupReading.class);
        Setup setup = check(r, forModel, fileName);
        log.info("Project setup title={} questions={}", setup.titleSource(), setup.questions().size());
        return setup;
    }

    /** Code checks: the title and questions must be the document's own words; a suggested title needs its terms. */
    static Setup check(SetupReading r, String text, String fileName) {
        String title = null;
        TitleSource source = TitleSource.NONE;
        String front = text.length() > 4_000 ? text.substring(0, 4_000) : text;
        if (r != null && r.title() != null && !CHAPTER_HEADING.matcher(r.title()).find()) {
            title = Grounding.findSpan(r.title(), front, 3);
            source = title == null ? TitleSource.NONE : TitleSource.DOCUMENT;
        }
        if (title == null && r != null && r.workingTitle() != null && !CHAPTER_HEADING.matcher(r.workingTitle()).find()
                && mostlyFrom(r.workingTitle(), text)) {
            title = ResearchInsightsService.shorten(r.workingTitle(), 300);
            source = TitleSource.SUGGESTED;
        }
        if (title == null) {
            title = fromFileName(fileName);
        }

        List<ResearchProjectStore.QuestionInput> questions = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        if (r != null && r.questions() != null) {
            for (SetupQuestion q : r.questions()) {
                if (q == null || q.question() == null || questions.size() >= MAX_QUESTIONS) {
                    continue;
                }
                String found = Grounding.findSpan(LEADING_NUMBER.matcher(q.question()).replaceFirst(""), text, 5);
                if (found == null || !seen.add(Grounding.words(found))) {
                    continue;
                }
                found = withEndPunctuation(found, text);
                String expected = q.expectedAnswer() == null || NULL_HYPOTHESIS.matcher(q.expectedAnswer()).find()
                        ? null : Grounding.findSpan(LEADING_NUMBER.matcher(q.expectedAnswer()).replaceFirst(""), text, 5);
                expected = expected == null ? null : withEndPunctuation(expected, text);
                questions.add(new ResearchProjectStore.QuestionInput(null, cap(found, ResearchProjectStore.MAX_QUESTION_CHARS),
                        expected == null ? null : cap(expected, ResearchProjectStore.MAX_HYPOTHESIS_CHARS)));
            }
        }
        String field = r == null ? null : ResearchWorkspaceStore.cap(r.field(), 60);
        return new Setup(title, source, field, questions);
    }

    /** At least 70% of the title's content words appear in the document. */
    static boolean mostlyFrom(String title, String text) {
        String material = Grounding.material(text);
        List<String> words = java.util.Arrays.stream(Grounding.words(title).split(" ")).filter(w -> w.length() >= 4).toList();
        if (words.size() < 2) {
            return false;
        }
        long found = words.stream().filter(w -> material.contains(" " + w + " ")).count();
        return found * 10 >= words.size() * 7L;
    }

    /** The span ends at its last word; keep the "?" or "." that follows it in the document. */
    static String withEndPunctuation(String span, String text) {
        int at = text.indexOf(span);
        if (at < 0) {
            return span;
        }
        int end = at + span.length();
        return end < text.length() && (text.charAt(end) == '?' || text.charAt(end) == '.') ? span + text.charAt(end) : span;
    }

    static String fromFileName(String fileName) {
        if (fileName == null) {
            return "My capstone project";
        }
        String base = fileName.replaceAll("^.*[\\\\/]", "").replaceAll("\\.[A-Za-z0-9]{1,5}$", "").replaceAll("[_-]+", " ").strip();
        return base.length() < 5 ? "My capstone project" : base;
    }

    private static String cap(String s, int max) {
        String t = s.strip();
        return t.length() > max ? t.substring(0, max) : t;
    }
}
