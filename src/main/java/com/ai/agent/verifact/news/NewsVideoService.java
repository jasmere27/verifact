package com.ai.agent.verifact.news;

import com.ai.agent.verifact.ai.LlmClient;
import com.ai.agent.verifact.evidence.Grounding;
import com.ai.agent.verifact.news.NewsVideos.Chapter;
import com.ai.agent.verifact.news.NewsVideos.ClaimVideos;
import com.ai.agent.verifact.news.NewsVideos.Kind;
import com.ai.agent.verifact.news.NewsVideos.Stance;
import com.ai.agent.verifact.news.NewsVideos.SupportingVideo;
import com.ai.agent.verifact.video.FoundVideo;
import com.ai.agent.verifact.video.VideoSearch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds videos for a story's main claims and classifies them against each claim, using only what each
 * video's platform publishes about it. The model never adds videos, and its words are checked in code.
 */
@Service
public class NewsVideoService {

    private static final Logger log = LoggerFactory.getLogger(NewsVideoService.class);

    static final int PER_CLAIM = 6;
    static final int MAX_DESCRIPTION = 900;
    static final String NOTICE = "Videos are matched using their titles, descriptions and chapter lists as published on "
            + "the platform; the videos themselves aren't downloaded or analysed, and transcripts of other people's YouTube "
            + "videos aren't available to us. \"Earliest upload found\" is the oldest among these results, not proof of the "
            + "original. Watch the original before relying on it.";

    private static final Pattern CHAPTER = Pattern.compile(
            "(?m)^\\s*[\\[(]?((?:\\d{1,2}:)?\\d{1,2}:\\d{2})[\\])]?\\s*[-–—:|]?\\s*(\\S.{1,80}?)\\s*$");

    private final LlmClient llm;
    private final VideoSearch search;
    private final int claimsToSearch;

    public NewsVideoService(LlmClient llm, VideoSearch search, @Value("${app.news.video-claims:4}") int claimsToSearch) {
        this.llm = llm;
        this.search = search;
        this.claimsToSearch = claimsToSearch;
    }

    /** One claim to find videos for: its id in the check and the text to search and judge against. */
    public record ClaimInput(String claimId, String claim) {}

    public NewsVideos find(List<ClaimInput> claims) {
        if (!search.enabled()) {
            return NewsVideos.none("Video search isn't configured on this server.");
        }
        List<ClaimInput> selected = claims.stream().limit(Math.max(0, claimsToSearch)).toList();
        List<String> limitations = new ArrayList<>();
        if (claims.size() > selected.size()) {
            limitations.add("Videos were searched for the first " + selected.size() + " claims only (daily search limits).");
        }
        List<ClaimVideos> out = new ArrayList<>();
        boolean quota = false;
        int failed = 0;
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<ClaimVideos>> jobs = new ArrayList<>();
            for (ClaimInput c : selected) {
                jobs.add(pool.submit(() -> forClaim(c)));
            }
            for (Future<ClaimVideos> f : jobs) {
                try {
                    out.add(f.get());
                } catch (ExecutionException e) {
                    failed++;
                    if (e.getCause() instanceof VideoSearch.VideoSearchException v && v.quotaExceeded()) {
                        quota = true;
                    }
                    log.warn("Video search failed: {}", e.getCause() == null ? "?" : e.getCause().getMessage());
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (quota) {
            limitations.add("Today's video search limit was reached; try again after it resets (midnight Pacific time).");
        } else if (failed > 0) {
            limitations.add("Video search failed for " + failed + " claim(s); the rest of the check is unaffected.");
        }
        log.info("News videos claims={} failed={} videos={}", selected.size(), failed,
                out.stream().mapToInt(c -> c.videos().size()).sum());
        return new NewsVideos(out, true, limitations, NOTICE);
    }

    private ClaimVideos forClaim(ClaimInput c) throws InterruptedException {
        String query = query(c.claim());
        List<FoundVideo> found = search.search(query, PER_CLAIM);
        if (found.isEmpty()) {
            return new ClaimVideos(c.claimId(), query, List.of());
        }
        Map<String, FoundVideo> byId = new LinkedHashMap<>();
        Map<String, List<Chapter>> chapters = new LinkedHashMap<>();
        List<NewsPrompts.VideoLine> lines = new ArrayList<>();
        for (int i = 0; i < found.size(); i++) {
            FoundVideo v = found.get(i);
            String id = "V" + (i + 1);
            byId.put(id, v);
            List<Chapter> ch = chapters(v.description());
            chapters.put(id, ch);
            String d = v.description() == null ? "" : v.description();
            lines.add(new NewsPrompts.VideoLine(id, v.title(), v.channel(),
                    v.publishedAt() == null ? null : DateTimeFormatter.ISO_LOCAL_DATE.format(v.publishedAt().atZone(java.time.ZoneOffset.UTC)),
                    d.length() > MAX_DESCRIPTION ? d.substring(0, MAX_DESCRIPTION) : d, ch.stream().map(Chapter::label).toList()));
        }
        String nonce = UUID.randomUUID().toString().replace("-", "");
        NewsOutputs.VideoReview review = llm.generateQuick(NewsPrompts.withNonce(NewsPrompts.VIDEO_SYSTEM, nonce),
                NewsPrompts.videoUser(nonce, c.claim(), lines), NewsOutputs.VideoReview.class);
        List<SupportingVideo> videos = validate(review, byId, chapters, c.claim());
        return new ClaimVideos(c.claimId(), query, markEarliest(videos));
    }

    static List<SupportingVideo> validate(NewsOutputs.VideoReview review, Map<String, FoundVideo> byId,
                                          Map<String, List<Chapter>> chapters, String claim) {
        List<SupportingVideo> out = new ArrayList<>();
        if (review == null || review.videos() == null) {
            return out;
        }
        Set<String> seen = new LinkedHashSet<>();
        for (NewsOutputs.VideoNote n : review.videos()) {
            String id = n == null || n.videoId() == null ? null : n.videoId().trim().toUpperCase(Locale.ROOT);
            FoundVideo v = id == null ? null : byId.get(id);
            if (v == null || !n.relevant() || !seen.add(id)) {
                continue;
            }
            Stance stance = parse(Stance.class, n.stance(), null);
            String own = v.title() + "\n" + (v.description() == null ? "" : v.description());
            String quote = Grounding.findSpan(n.quote(), own, 4);
            String why = clean(n.why(), 300);
            if (stance == null || quote == null || why == null
                    || !Grounding.supported(why, Grounding.material(claim, v.title(), v.description() == null ? "" : v.description()))) {
                continue;
            }
            List<String> made = new ArrayList<>();
            if (n.claimsMade() != null) {
                for (String m : n.claimsMade()) {
                    String span = Grounding.findSpan(m, own, 4);
                    if (span != null && span.length() <= 300 && !made.contains(span)) {
                        made.add(span);
                    }
                    if (made.size() == 3) {
                        break;
                    }
                }
            }
            List<Chapter> ch = chapters.getOrDefault(id, List.of());
            Chapter at = n.chapter() == null ? null : ch.stream()
                    .filter(x -> Grounding.words(x.label()).equals(Grounding.words(n.chapter()))).findFirst().orElse(null);
            List<String> frames = "youtube".equals(v.platform())
                    ? List.of(1, 2, 3).stream().map(k -> "https://i.ytimg.com/vi/" + v.videoId() + "/" + k + ".jpg").toList()
                    : List.of();
            out.add(new SupportingVideo(v.platform(), v.videoId(), v.url(), v.title(), v.channel(), v.publishedAt(),
                    v.durationSeconds(), v.thumbnailUrl(), frames, ch, at, stance, parse(Kind.class, n.kind(), Kind.OTHER),
                    why, quote, made, false));
        }
        return out;
    }

    /** Flags the oldest upload when at least two relevant videos have dates. */
    static List<SupportingVideo> markEarliest(List<SupportingVideo> videos) {
        List<SupportingVideo> dated = videos.stream().filter(v -> v.publishedAt() != null).toList();
        if (dated.size() < 2) {
            return videos;
        }
        SupportingVideo earliest = dated.stream().min(Comparator.comparing(SupportingVideo::publishedAt)).orElseThrow();
        return videos.stream().map(v -> v != earliest ? v : new SupportingVideo(v.platform(), v.videoId(), v.url(), v.title(),
                v.channel(), v.publishedAt(), v.durationSeconds(), v.thumbnailUrl(), v.keyFrames(), v.chapters(), v.relevantAt(),
                v.stance(), v.kind(), v.why(), v.quote(), v.claimsMade(), true)).toList();
    }

    /** "0:00 Intro", "12:30 - Mayor speaks", "[1:02:03] Q&A": chapters as the uploader listed them. */
    static List<Chapter> chapters(String description) {
        List<Chapter> out = new ArrayList<>();
        if (description == null) {
            return out;
        }
        Matcher m = CHAPTER.matcher(description);
        while (m.find() && out.size() < 40) {
            String[] parts = m.group(1).split(":");
            int seconds = 0;
            for (String p : parts) {
                seconds = seconds * 60 + Integer.parseInt(p);
            }
            out.add(new Chapter(seconds, m.group(2).strip()));
        }
        return out;
    }

    static String query(String claim) {
        String q = claim == null ? "" : claim.replaceAll("[\"“”]", "").replaceAll("\\s+", " ").strip();
        return q.length() > 120 ? q.substring(0, q.lastIndexOf(' ', 120) > 40 ? q.lastIndexOf(' ', 120) : 120) : q;
    }

    private static <E extends Enum<E>> E parse(Class<E> type, String value, E fallback) {
        if (value == null) {
            return fallback;
        }
        try {
            return Enum.valueOf(type, value.trim().toUpperCase(Locale.ROOT).replace(' ', '_'));
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }

    private static String clean(String s, int max) {
        if (s == null || s.isBlank()) {
            return null;
        }
        String t = s.strip().replaceAll("\\s+", " ");
        return t.length() > max ? t.substring(0, max) : t;
    }
}
