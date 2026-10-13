package com.ai.agent.verifact.intelligence.responses;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Response Studio read models. Drafts are AI-generated starting points for human review; nothing is ever published. */
public final class ResponseDraft {

    private ResponseDraft() {
    }

    public enum Status { DRAFT, IN_REVIEW, APPROVED }

    /** @param sourceIds investigation source ids (E1…) the fact rests on */
    public record KeyFact(String text, List<String> sourceIds) {}

    public record Citation(String sourceId, String url, String domain, String title) {}

    /**
     * @param evidenceWarning shown above the draft when the evidence doesn't support a definitive response
     * @param removedForGrounding how many model sentences or facts were removed because their figures weren't in the evidence
     */
    public record Meta(List<KeyFact> keyFacts, List<String> uncertainties, List<String> nextSteps, List<Citation> citations,
                       String evidenceWarning, int removedForGrounding, boolean aiGenerated) {}

    public record Revision(int revision, String body, UUID editedBy, String editedByEmail, String note, Instant createdAt) {}

    public record Summary(UUID id, DraftKind kind, String kindLabel, String title, Status status, int revision, UUID investigationId,
                          String investigationTitle, Integer investigationVersion, UUID incidentId, Instant createdAt, Instant updatedAt,
                          String approvedByEmail, Instant approvedAt) {}

    public record Detail(Summary summary, String body, Meta meta, List<Revision> revisions) {}
}
