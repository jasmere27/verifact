package com.ai.agent.verifact.research;

import java.util.List;

/**
 * A work found by a discovery search, with what's needed to present it honestly: the OpenAlex id
 * (every work has one, even without a DOI), its type, and its authors' institution countries (the
 * basis for "local" vs "foreign", decided in code).
 *
 * @param countries ISO 3166 alpha-2 codes, upper case, e.g. ["PH", "US"]
 */
public record DiscoveredWork(ScholarlyWork work, String openAlexId, String type, List<String> countries,
                             String landingUrl) {}
