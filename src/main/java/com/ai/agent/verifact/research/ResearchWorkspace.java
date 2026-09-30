package com.ai.agent.verifact.research;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A Student Research Mode workspace: topic, saved sources organised by purpose, notes, the student's
 * uploaded draft (extracted text and analysis; never the file) and the latest insights. Saved
 * sources are re-fetched from OpenAlex by the server when added, so their details can't be forged.
 *
 * @param country ISO code used to split local/foreign studies, e.g. "PH"; null for none
 * @param expiresAt deleted automatically at this time unless changed again (90 days after the last change)
 */
public record ResearchWorkspace(UUID id, Instant createdAt, Instant updatedAt, Instant expiresAt, String topic,
                                String field, String country, List<SavedSource> sources, String notes, Draft draft,
                                Insights insights) {

    public enum Folder { RRL, RRS, THEORY, CONCEPT, METHOD, EVIDENCE, OTHER }

    public record SavedSource(String key, Folder folder, Discovery.FoundSource source, String studentNote, Instant savedAt) {}

    /** API response: {@code editToken} only in the response that created the workspace. */
    public record View(ResearchWorkspace workspace, String editToken) {}
}
