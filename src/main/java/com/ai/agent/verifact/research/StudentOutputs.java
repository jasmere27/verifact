package com.ai.agent.verifact.research;

import java.util.List;

/** Structured model output for draft reading and insights. Everything is checked in code before use. */
final class StudentOutputs {

    private StudentOutputs() {
    }

    record DraftReading(String summary, List<String> concepts, List<Uncited> statements) {}

    record Uncited(String quote, String why) {}

    record InsightDraft(List<GapNote> gaps, List<VariableNote> variables) {}

    record GapNote(String statement, String kind, List<String> sourceIds) {}

    record VariableNote(String name, String role) {}

    record RelationReview(List<RelationNote> works) {}

    record RelationNote(String workId, String kind, String how, String quote) {}
}
