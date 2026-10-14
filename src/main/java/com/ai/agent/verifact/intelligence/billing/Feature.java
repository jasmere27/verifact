package com.ai.agent.verifact.intelligence.billing;

/** Metered, plan-limited actions. Each successful (or attempted, for paid external calls) action is one usage event. */
public enum Feature {
    /** One execution of a monitoring project's connectors (scheduled or manual). */
    MONITORING_RUN,
    /** One evidence investigation run (claims, web evidence, assessment). */
    INVESTIGATION,
    /** One Response Studio draft generation. */
    RESPONSE_DRAFT,
    /** One generated report. */
    REPORT
}
