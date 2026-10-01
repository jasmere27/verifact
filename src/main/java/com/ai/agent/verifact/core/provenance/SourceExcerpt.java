package com.ai.agent.verifact.core.provenance;

/** A source's own words for or against a claim (verbatim, checked in code by {@link CitationValidator}). */
public record SourceExcerpt(String sourceId, String excerpt) {}
