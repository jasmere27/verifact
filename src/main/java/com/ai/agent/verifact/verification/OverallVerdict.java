package com.ai.agent.verifact.verification;

import java.util.List;

/** Summary across all checked claims. Computed by the backend, never by the model. */
public enum OverallVerdict {
    SUPPORTED,
    PARTLY_SUPPORTED,
    MISLEADING,
    CONTRADICTED,
    INSUFFICIENT_EVIDENCE,
    /** Claims received different verdicts. */
    MIXED;

    static OverallVerdict of(List<Verdict> verdicts) {
        if (verdicts.isEmpty()) {
            return INSUFFICIENT_EVIDENCE;
        }
        Verdict first = verdicts.get(0);
        return verdicts.stream().allMatch(v -> v == first) ? valueOf(first.name()) : MIXED;
    }
}
