package com.ai.agent.verifact.news;

import java.time.Instant;
import java.util.Map;

/** The editor's decisions on a {@link NewsCheck}: the human-review half of the workspace. */
public record NewsReview(Map<String, Decision> decisions, String editorNote, Instant updatedAt) {

    public enum Status { UNREVIEWED, CONFIRMED, DISPUTED, NEEDS_WORK }

    public record Decision(Status status, String note) {}

    static NewsReview empty(Instant now) {
        return new NewsReview(Map.of(), null, now);
    }
}
