package com.ai.agent.verifact.research;

import com.ai.agent.verifact.research.ResearchProject.GapNote;
import com.ai.agent.verifact.research.ResearchProject.LibraryItem;
import com.ai.agent.verifact.research.ResearchProject.Question;

import java.util.List;

/** What a project stores (as JSON in {@code research_projects.data_json}); progress and next steps are derived. */
record ProjectData(String title, String field, String country, List<Question> questions, List<LibraryItem> library,
                   List<GapNote> gaps, String notes, Draft draft, Insights insights) {

    ProjectData {
        questions = questions == null ? List.of() : List.copyOf(questions);
        library = library == null ? List.of() : List.copyOf(library);
        gaps = gaps == null ? List.of() : List.copyOf(gaps);
    }

    ProjectData withLibrary(List<LibraryItem> next) {
        return new ProjectData(title, field, country, questions, next, gaps, notes, draft, insights);
    }

    ProjectData withDraft(Draft next) {
        return new ProjectData(title, field, country, questions, library, gaps, notes, next, insights);
    }

    ProjectData withInsights(Insights next) {
        return new ProjectData(title, field, country, questions, library, gaps, notes, draft, next);
    }
}
