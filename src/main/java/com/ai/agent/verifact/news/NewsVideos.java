package com.ai.agent.verifact.news;

import java.time.Instant;
import java.util.List;

/**
 * Supporting videos for a story's claims: public videos found by search, classified from their own titles,
 * descriptions and chapter lists (never their content, which isn't downloaded). Every quote and "claim made"
 * is a verbatim span of the title or description; a relevant timestamp must be one of the video's own chapters.
 */
public record NewsVideos(List<ClaimVideos> claims, boolean searched, List<String> limitations, String notice) {

    public enum Stance { SUPPORTS, CONTRADICTS, CONTEXT }

    /** The model's reading of the channel and description: labelled as such in the UI. */
    public enum Kind { NEWS_REPORT, OFFICIAL, EYEWITNESS, OTHER }

    public record Chapter(int seconds, String label) {}

    /**
     * @param keyFrames      YouTube's automatic frames from the video (small stills), for a quick look
     * @param relevantAt     a chapter the video lists that covers the claim, or null
     * @param earliestFound  the earliest upload among the relevant videos found for this claim (not proof it's the original)
     * @param embeddable     whether the uploader allows playing it inside VeriFact; null when unknown (older checks)
     */
    public record SupportingVideo(String platform, String videoId, String url, String title, String channel,
                                  Instant publishedAt, Integer durationSeconds, String thumbnailUrl, List<String> keyFrames,
                                  List<Chapter> chapters, Chapter relevantAt, Stance stance, Kind kind, String why,
                                  String quote, List<String> claimsMade, boolean earliestFound, Boolean embeddable) {}

    public record ClaimVideos(String claimId, String query, List<SupportingVideo> videos) {}

    static NewsVideos none(String reason) {
        return new NewsVideos(List.of(), false, reason == null ? List.of() : List.of(reason), NewsVideoService.NOTICE);
    }
}
