package com.ai.agent.verifact.core.assess;

import com.ai.agent.verifact.core.provenance.SourceExcerpt;

/**
 * A claim's verdict after the core's checks: every excerpt is a retrieved source's own words, and the verdict
 * has the excerpt it needs.
 *
 * @param flagsBacked     whether product flags about the claim (outdated, misattributed…) may stand: they too need an excerpt
 * @param sourcesConflict one source backs the claim and a different one contradicts it
 * @param explanation     the model's explanation if grounded in the claim and excerpts, else null
 */
public record ClaimFinding(Verdict verdict, SourceExcerpt supporting, SourceExcerpt contradicting, boolean flagsBacked,
                           boolean sourcesConflict, String explanation) {}
