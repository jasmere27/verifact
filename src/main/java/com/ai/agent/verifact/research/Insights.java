package com.ai.agent.verifact.research;

import java.time.Instant;
import java.util.List;

/**
 * What the saved sources say about the student's study. {@link Coverage} is counted in code; gaps,
 * relations and framework variables are the AI's reading of the saved abstracts, each tied to saved
 * sources (and, for relations, a verbatim abstract quote) and labelled as interpretation.
 */
public record Insights(Instant generatedAt, int basedOnSources, Coverage coverage, List<Gap> gaps, List<Relation> relations,
                       List<Variable> framework, List<String> limitations, String notice) {

    public record Coverage(int total, int local, int foreign, int withAbstract, Integer oldestYear, Integer newestYear,
                           int lastFiveYears, int reviews, int retracted) {}

    public enum GapKind { POPULATION, SETTING, METHOD, VARIABLE, TIME, EVIDENCE, OTHER }

    /** An interpretation, never a fact: {@code basis} lists the saved sources it rests on. */
    public record Gap(String statement, GapKind kind, List<String> basis) {}

    public enum RelationKind { SAME_FOCUS, SAME_METHOD, DIFFERENT_SETTING, SUPPORTS, CONTRADICTS, BACKGROUND }

    public record Relation(String key, String title, RelationKind kind, String how, String quote) {}

    public enum Role { INDEPENDENT, DEPENDENT, MEDIATOR, MODERATOR, CONTEXT }

    /** VERIFIED when at least one saved source's title or abstract names it; its keys are listed. */
    public record Variable(String name, Role role, List<String> sourceKeys, Discovery.Verification verification) {}
}
