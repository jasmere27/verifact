/**
 * VeriFact Intelligence (ADR-26): the B2B workspace for brand monitoring, evidence investigations, incidents,
 * response drafts, reports, subscriptions and usage. Every table is organisation-owned; every request passes
 * {@link com.ai.agent.verifact.intelligence.IntelligenceAccess#require} and every query filters by the
 * organisation id it returns. Data access uses {@code JdbcClient} with explicit SQL so the tenant filter is
 * visible in each statement.
 */
package com.ai.agent.verifact.intelligence;
