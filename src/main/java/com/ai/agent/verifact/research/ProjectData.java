package com.ai.agent.verifact.research;

import com.ai.agent.verifact.research.ResearchProject.GapNote;
import com.ai.agent.verifact.research.ResearchProject.LibraryItem;
import com.ai.agent.verifact.research.ResearchProject.LinkSuggestion;
import com.ai.agent.verifact.research.ResearchProject.Question;

import java.util.List;

/**
 * What a project stores (as JSON in {@code research_projects.data_json}); progress and next steps are derived.
 *
 * @param suggestions AI-suggested source ↔ question links the student hasn't reviewed (ADR-24)
 * @param dismissed   rejected suggestions as {@link #pair} keys, so they aren't suggested again
 */
record ProjectData(String title, String field, String country, List<Question> questions, List<LibraryItem> library,
                   List<GapNote> gaps, String notes, Draft draft, Insights insights, List<LinkSuggestion> suggestions,
                   List<String> dismissed) {

    ProjectData {
        questions = questions == null ? List.of() : List.copyOf(questions);
        library = library == null ? List.of() : List.copyOf(library);
        gaps = gaps == null ? List.of() : List.copyOf(gaps);
        suggestions = suggestions == null ? List.of() : List.copyOf(suggestions);
        dismissed = dismissed == null ? List.of() : List.copyOf(dismissed);
    }

    ProjectData(String title, String field, String country, List<Question> questions, List<LibraryItem> library,
                List<GapNote> gaps, String notes, Draft draft, Insights insights) {
        this(title, field, country, questions, library, gaps, notes, draft, insights, List.of(), List.of());
    }

    /** Identifies a source ↔ question pair; library keys (DOIs) never contain a space. */
    static String pair(String key, String questionId) {
        return key.toLowerCase(java.util.Locale.ROOT) + " " + questionId;
    }

    ProjectData withLibrary(List<LibraryItem> next) {
        return new ProjectData(title, field, country, questions, next, gaps, notes, draft, insights, suggestions, dismissed);
    }

    ProjectData withDraft(Draft next) {
        return new ProjectData(title, field, country, questions, library, gaps, notes, next, insights, suggestions, dismissed);
    }

    ProjectData withInsights(Insights next) {
        return new ProjectData(title, field, country, questions, library, gaps, notes, draft, next, suggestions, dismissed);
    }

    ProjectData withLinks(List<LibraryItem> nextLibrary, List<LinkSuggestion> nextSuggestions, List<String> nextDismissed) {
        return new ProjectData(title, field, country, questions, nextLibrary, gaps, notes, draft, insights, nextSuggestions, nextDismissed);
    }
}
