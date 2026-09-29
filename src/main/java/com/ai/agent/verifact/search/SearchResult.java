package com.ai.agent.verifact.search;

/**
 * One web search hit. Content is untrusted (it comes from arbitrary websites).
 *
 * @param publishedDate as reported by the provider (format varies), or null if unknown
 */
public record SearchResult(String title, String url, String snippet, String publishedDate) {}
