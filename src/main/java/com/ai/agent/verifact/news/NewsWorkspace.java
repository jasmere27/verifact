package com.ai.agent.verifact.news;

/**
 * What the API returns: the check, the review, and (only in the response that created it) the edit
 * token. Anyone with the link can read a workspace; only the token holder can change the review.
 */
public record NewsWorkspace(NewsCheck check, NewsReview review, String editToken) {}
