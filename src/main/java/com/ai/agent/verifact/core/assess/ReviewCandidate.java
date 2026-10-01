package com.ai.agent.verifact.core.assess;

/**
 * A model's assessment of one claim, before any check. Products map their own model output to this.
 *
 * @param verdict     a {@link Verdict} name as the model wrote it
 * @param explanation the model's short reason; kept only if grounded
 */
public record ReviewCandidate(String verdict, String supportingSourceId, String supportingExcerpt,
                              String contradictingSourceId, String contradictingExcerpt, String explanation) {}
