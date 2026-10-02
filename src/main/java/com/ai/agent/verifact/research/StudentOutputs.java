package com.ai.agent.verifact.research;

import java.util.List;

/** Structured model output for draft reading and insights. Everything is checked in code before use. */
final class StudentOutputs {

    private StudentOutputs() {
    }

    record DraftReading(String summary, List<String> concepts, List<Uncited> statements) {}

    record Uncited(String quote, String why) {}

    /** A research paper as the model read it; title/authors/year/doi are used only to look the paper up. */
    record PaperReading(String title, List<String> authors, Integer year, String doi, String plainSummary, List<PaperNote> findings,
                        List<MethodNote> method, List<PaperNote> limitations) {}

    record PaperNote(String statement, String quote) {}

    record MethodNote(String aspect, String statement, String quote) {}

    record InsightDraft(List<GapNote> gaps, List<VariableNote> variables) {}

    record GapNote(String statement, String kind, List<String> sourceIds) {}

    record VariableNote(String name, String role) {}

    record RelationReview(List<RelationNote> works) {}

    record RelationNote(String workId, String kind, String how, String quote) {}

    record LinkReview(List<LinkProposal> links) {}

    record LinkProposal(String workId, String questionId, String role, String stance, String how, String quote) {}
}
