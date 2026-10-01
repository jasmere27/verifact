package com.ai.agent.verifact.video;

import java.time.Instant;

/**
 * A video's public metadata from its platform. No video or caption content: YouTube lets only a video's
 * owner download captions, and downloading videos breaks its terms.
 *
 * @param durationSeconds null when unknown
 */
public record FoundVideo(String platform, String videoId, String url, String title, String channel, String channelId,
                         Instant publishedAt, Integer durationSeconds, String description, String thumbnailUrl) {}
