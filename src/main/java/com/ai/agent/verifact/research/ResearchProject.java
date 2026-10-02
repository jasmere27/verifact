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
                              String notes, Draft draft, Insights insights, List<ProjectFile.Summary> files, Progress progress,
                              List<NextStep> nextSteps) {

    /** A research question. {@code id} is stable; the label (RQ1, RQ2…) follows the order. */
    public record Question(String id, String text) {}

    public enum ReadingStatus { TO_READ, READ, CITED }

    /**
     * A saved source in the evidence library.
     *
     * @param source      verified index record (title, authors, year, venue, DOI, local/foreign, retracted)
     * @param note        why it matters, in the student's words
     * @param keyFindings the student's summary of its findings
     * @param method      the study's method, as the student recorded it
     * @param questionIds research questions this source helps answer
     */
    public record LibraryItem(String key, Folder folder, Discovery.FoundSource source, ReadingStatus status, String note,
                              String keyFindings, String method, List<String> questionIds, Instant savedAt) {}

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
        OPEN_FILES
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
