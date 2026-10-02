package com.ai.agent.verifact.common;

import java.time.Duration;

/** How long stored user content is kept (ADR-19); the Privacy Policy promises this, so change both together. */
public final class Retention {

    public static final Duration PERIOD = Duration.ofDays(90);

    /** Capstone projects (ADR-21) run for a school year or more: kept 12 months after the last change. */
    public static final Duration PROJECT_PERIOD = Duration.ofDays(365);

    private Retention() {
    }
}
