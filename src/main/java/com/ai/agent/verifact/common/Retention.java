package com.ai.agent.verifact.common;

import java.time.Duration;

/** How long stored user content is kept (ADR-19); the Privacy Policy promises this, so change both together. */
public final class Retention {

    public static final Duration PERIOD = Duration.ofDays(90);

    private Retention() {
    }
}
