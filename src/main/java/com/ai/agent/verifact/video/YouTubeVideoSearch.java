package com.ai.agent.verifact.video;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * YouTube Data API v3: {@code search.list} for ids, then {@code videos.list} for full descriptions and
 * durations. Metadata only. The key never appears in logs or exception messages.
 */
@Component
public class YouTubeVideoSearch implements VideoSearch {

    static final String API = "https://www.googleapis.com/youtube/v3";
    static final Pattern VIDEO_ID = Pattern.compile("[A-Za-z0-9_-]{11}");

    private final RestClient restClient;
    private final JsonMapper jsonMapper;
    private final String apiKey;

    public YouTubeVideoSearch(RestClient.Builder builder, JsonMapper jsonMapper, @Value("${app.youtube.api-key:}") String apiKey) {
        this.restClient = builder.build();
        this.jsonMapper = jsonMapper;
        this.apiKey = apiKey == null ? "" : apiKey.strip();
    }

    @Override
    public boolean enabled() {
        return !apiKey.isEmpty();
    }

    @Override
    public List<FoundVideo> search(String query, int max) {
        if (!enabled()) {
            return List.of();
        }
        JsonNode found = get(UriComponentsBuilder.fromUriString(API + "/search")
                .queryParam("part", "snippet").queryParam("type", "video").queryParam("maxResults", Math.min(max, 10))
                .queryParam("safeSearch", "moderate").queryParam("q", query).queryParam("key", apiKey)
                .encode().build().toUri());
        List<String> ids = new ArrayList<>();
        for (JsonNode item : found.path("items")) {
            String id = item.path("id").path("videoId").asString("");
            if (VIDEO_ID.matcher(id).matches() && !ids.contains(id)) {
                ids.add(id);
            }
        }
        if (ids.isEmpty()) {
            return List.of();
        }
        JsonNode details = get(UriComponentsBuilder.fromUriString(API + "/videos")
                .queryParam("part", "snippet,contentDetails").queryParam("id", String.join(",", ids)).queryParam("key", apiKey)
                .encode().build().toUri());
        Map<String, FoundVideo> byId = new LinkedHashMap<>();
        for (JsonNode item : details.path("items")) {
            FoundVideo v = parse(item);
            if (v != null) {
                byId.put(v.videoId(), v);
            }
        }
        List<FoundVideo> out = new ArrayList<>();
        ids.forEach(id -> {
            if (byId.containsKey(id)) {
                out.add(byId.get(id)); // keep YouTube's relevance order
            }
        });
        return out;
    }

    static FoundVideo parse(JsonNode item) {
        String id = item.path("id").asString("");
        if (!VIDEO_ID.matcher(id).matches()) {
            return null;
        }
        JsonNode s = item.path("snippet");
        String title = s.path("title").asString("");
        if (title.isBlank()) {
            return null;
        }
        Instant published = null;
        try {
            String p = s.path("publishedAt").asString("");
            published = p.isBlank() ? null : Instant.parse(p);
        } catch (DateTimeParseException ignored) {
            // leave unknown
        }
        Integer seconds = null;
        try {
            String d = item.path("contentDetails").path("duration").asString("");
            seconds = d.isBlank() ? null : (int) Duration.parse(d).getSeconds();
        } catch (DateTimeParseException ignored) {
            // e.g. "P0D" for live streams parses; anything else stays unknown
        }
        JsonNode thumbs = s.path("thumbnails");
        String thumb = thumbs.path("high").path("url").asString(thumbs.path("medium").path("url").asString(""));
        return new FoundVideo("youtube", id, "https://www.youtube.com/watch?v=" + id, title, s.path("channelTitle").asString(""),
                s.path("channelId").asString(""), published, seconds, s.path("description").asString(""),
                thumb.startsWith("https://i.ytimg.com/") ? thumb : "https://i.ytimg.com/vi/" + id + "/hqdefault.jpg");
    }

    private JsonNode get(URI uri) {
        String body;
        try {
            body = restClient.get().uri(uri).retrieve().body(String.class);
        } catch (RestClientResponseException e) {
            String text = e.getResponseBodyAsString();
            boolean quota = e.getStatusCode().value() == 403 && (text.contains("quotaExceeded") || text.contains("rateLimitExceeded"));
            throw new VideoSearchException("YouTube returned HTTP " + e.getStatusCode().value(), quota);
        } catch (RestClientException e) {
            throw new VideoSearchException("YouTube request failed: " + e.getClass().getSimpleName(), false);
        }
        try {
            return jsonMapper.readTree(body == null ? "{}" : body);
        } catch (RuntimeException e) {
            throw new VideoSearchException("YouTube returned malformed JSON", false);
        }
    }
}
