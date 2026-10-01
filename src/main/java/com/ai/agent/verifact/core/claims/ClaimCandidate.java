package com.ai.agent.verifact.core.claims;

import java.util.List;

/**
 * A claim as a model proposed it, before any check. Products map their own model output to this.
 *
 * @param type          the product's claim type name, as the model wrote it
 * @param quote         the input's own sentence or clause the claim comes from
 * @param claim         the claim restated to stand alone
 * @param speaker       who the input says said it, if anyone
 * @param quotedWords   for quotes: the exact words inside the quotation marks
 * @param searchQueries web search queries to verify it
 */
public record ClaimCandidate(String type, String quote, String claim, String speaker, String quotedWords,
                             List<String> searchQueries) {}
