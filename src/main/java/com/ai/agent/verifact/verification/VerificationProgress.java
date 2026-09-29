package com.ai.agent.verifact.verification;

import java.util.List;

/** Receives pipeline milestones as they happen, e.g. to stream them to the browser. */
public interface VerificationProgress {

    enum Stage { READING_INPUT, EXTRACTING_CLAIMS, SEARCHING, ASSESSING }

    VerificationProgress NONE = new VerificationProgress() {};

    default void stage(Stage stage) {
    }

    default void claims(List<String> claims) {
    }

    default void sources(int count, List<String> domains) {
    }
}
