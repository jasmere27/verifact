package com.ai.agent.verifact.research;

import com.ai.agent.verifact.research.ResearchWorkspace.Folder;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A capstone / research project owned by a signed-in student (ADR-21): the topic, research questions, an
 * evidence library of verified sources, the student's own research gap statements, notes, a draft and insights,
 * plus progress and next steps computed in code ({@link ProjectAdvisor}).
 *
 * <p>Integrity: every library source is an index record re-fetched by the server ({@link SourceVerifier});
 * the student's own fields (note, key findings, method) are theirs and are labelled as such in the UI.
 *
 * @param deletesAt deleted automatically at this time unless worked on again (12 months after the last change)
 */
public record ResearchProject(UUID id, Instant createdAt, Instant updatedAt, Instant deletesAt, String title, String field,
                              String country, List<Question> questions, List<LibraryItem> library, List<GapNote> gaps,
                              String notes, Draft draft, Insights insights, List<ProjectFile.Summary> files,
                              List<LinkSuggestion> suggestions, Progress progress, List<NextStep> nextSteps) {

    /**
     * A research question. {@code id} is stable; the label (RQ1, RQ2…) follows the order.
     *
     * @param hypothesis what the student expects to find, in their own words (optional); supporting and conflicting
     *                   studies are judged against it
     */
    public record Question(String id, String text, String hypothesis) {

        public Question(String id, String text) {
            this(id, text, null);
        }
    }

    /** What a source does for a research question (ADR-24). */
    public enum LinkRole {
        /** Reports a result that helps answer the question. */
        FINDING,
        /** A design, instrument or analysis the student could use. */
        METHOD,
        /** Definitions, context or theory. */
        BACKGROUND
    }

    /** A finding compared with the student's expected answer; only when the question has one. */
    public enum LinkStance { SUPPORTS, CONTRADICTS, MIXED }

    /**
     * The AI's reading of how a source helps answer a question, kept when the student accepts the link.
     *
     * @param how   one sentence (AI interpretation)
     * @param quote the source abstract's own words it rests on, verbatim
     */
    public record LinkNote(String questionId, LinkRole role, LinkStance stance, String how, String quote) {}

    /** A link the AI suggests between a library source and a research question, waiting for the student. */
    public record LinkSuggestion(String key, String title, String questionId, LinkRole role, LinkStance stance, String how,
                                 String quote) {}

    public enum ReadingStatus { TO_READ, READ, CITED }

    /**
     * A saved source in the evidence library.
     *
     * @param source      verified index record (title, authors, year, venue, DOI, local/foreign, retracted)
     * @param note        why it matters, in the student's words
     * @param keyFindings the student's summary of its findings
     * @param method      the study's method, as the student recorded it
     * @param questionIds research questions this source helps answer
     * @param linkNotes   for links the student accepted from a suggestion: how it helps, with the abstract's words
     */
    public record LibraryItem(String key, Folder folder, Discovery.FoundSource source, ReadingStatus status, String note,
                              String keyFindings, String method, List<String> questionIds, Instant savedAt, List<LinkNote> linkNotes) {

        public LibraryItem {
            questionIds = questionIds == null ? List.of() : List.copyOf(questionIds);
            linkNotes = linkNotes == null ? List.of() : List.copyOf(linkNotes);
        }

        public LibraryItem(String key, Folder folder, Discovery.FoundSource source, ReadingStatus status, String note,
                           String keyFindings, String method, List<String> questionIds, Instant savedAt) {
            this(key, folder, source, status, note, keyFindings, method, questionIds, savedAt, List.of());
        }
    }

    /** A research gap in the student's own words, with the saved sources it rests on. */
    public record GapNote(String id, String statement, List<String> sourceKeys) {}

    public record Milestone(String id, String label, boolean done, String detail) {}

    public record Progress(int percent, List<Milestone> milestones) {}

    public enum Priority { HIGH, MEDIUM, LOW }

    /** What the frontend button does. */
    public enum Action {
        ADD_QUESTIONS,
        FIND_SOURCES,
        LINK_SOURCES,
        OPEN_LIBRARY,
        REVIEW_RETRACTED,
        WRITE_GAP,
        GENERATE_INSIGHTS,
        UPLOAD_DRAFT,
        REVIEW_DRAFT_CLAIMS,
        CHECK_CITATIONS,
        /** Uploaded papers not yet in the library. */
        OPEN_FILES,
        /** AI-suggested source ↔ question links waiting for the student. */
        REVIEW_LINKS
    }

    /**
     * A concrete next step, computed in code from the project (never invented).
     *
     * @param category   for FIND_SOURCES: what to search for
     * @param questionId for FIND_SOURCES/LINK_SOURCES: the research question it's about, or null
     * @param fileId     for draft and paper steps: the file to open, or null
     * @param basis      the facts it rests on (counts, source keys), shown to the student
     */
    public record NextStep(String id, Priority priority, String title, String detail, Action action,
                           Discovery.Category category, String questionId, String fileId, List<String> basis) {}

    /** The list page: enough to choose a project. */
    public record Summary(UUID id, String title, Instant updatedAt, Instant deletesAt, int sources, int questions, int percent) {}
}
