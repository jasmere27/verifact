package com.ai.agent.verifact.intelligence.monitoring;

import com.ai.agent.verifact.ai.UsageScope;
import com.ai.agent.verifact.video.FoundVideo;
import com.ai.agent.verifact.video.VideoSearch;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Public YouTube videos uploaded within the project's look-back window: title, channel and description only
 * (the Data API gives no captions or speech for other people's videos, and comments aren't read). Each search
 * costs 100 of the API key's ~10,000 daily quota units, a quota NewsFact shares, so monitoring has its own daily
 * budget; once it's used up the connector reports itself unavailable for the day instead of returning nothing.
 */
@Component
public class YouTubeConnector implements SourceConnector {

    static final int MAX_DESCRIPTION = 600;

    private final VideoSearch videos;
    private final Clock clock;
    private final int queriesPerRun;
    private final int resultsPerQuery;
    private final int dailyBudget;
    private final AtomicInteger usedToday = new AtomicInteger();
    private volatile LocalDate budgetDay;

    public YouTubeConnector(VideoSearch videos, Clock clock,
                            @Value("${app.monitoring.youtube.queries-per-run:1}") int queriesPerRun,
                            @Value("${app.monitoring.youtube.results-per-query:10}") int resultsPerQuery,
                            @Value("${app.monitoring.youtube.daily-searches:40}") int dailyBudget) {
        this.videos = videos;
        this.clock = clock;
        this.queriesPerRun = Math.max(1, queriesPerRun);
        this.resultsPerQuery = Math.max(1, Math.min(resultsPerQuery, 10));
        this.dailyBudget = Math.max(0, dailyBudget);
    }

    @Override
    public String id() {
        return "YOUTUBE";
    }

    @Override
    public boolean enabledFor(MonitoringConfig config) {
        return config.sources().youtube();
    }

    @Override
    public boolean available() {
        return videos.enabled() && dailyBudget > 0;
    }

    @Override
    public List<Item> fetch(Request request) {
        Instant after = clock.instant().minus(Duration.ofDays(request.config().lookbackDays()));
        List<String> queries = request.queries().stream().limit(queriesPerRun).toList();
        List<Item> items = new ArrayList<>();
        int failures = 0;
        String lastError = null;
        for (String q : queries) {
            if (!take()) {
                if (items.isEmpty() && failures == 0) {
                    throw new ConnectorException("Today's YouTube search budget for monitoring is used up; it resets at midnight UTC.");
                }
                throw new PartialResult(items, "Today's YouTube search budget ran out during this run.");
            }
            try {
                UsageScope.searchCall();
                for (FoundVideo v : videos.search(q, resultsPerQuery, after)) {
                    String channel = v.channel() == null || v.channel().isBlank() ? "" : "Channel: " + v.channel() + ". ";
                    String description = v.description() == null ? "" : v.description().replaceAll("\\s+", " ").trim();
                    if (description.length() > MAX_DESCRIPTION) {
                        description = description.substring(0, MAX_DESCRIPTION) + "…";
                    }
                    items.add(new Item(v.url(), v.title(), channel + description,
                            v.publishedAt() == null ? null : v.publishedAt().atOffset(ZoneOffset.UTC).toLocalDate().toString()));
                }
            } catch (VideoSearch.VideoSearchException e) {
                failures++;
                lastError = e.quotaExceeded() ? "YouTube's daily API quota is used up; it resets at midnight Pacific time." : e.getMessage();
            }
        }
        if (failures > 0 && failures == queries.size()) {
            throw new ConnectorException(lastError);
        }
        if (failures > 0) {
            throw new PartialResult(items, lastError);
        }
        return items;
    }

    /** One search from today's budget (UTC day; in memory, so a restart resets it). */
    private synchronized boolean take() {
        LocalDate today = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
        if (!today.equals(budgetDay)) {
            budgetDay = today;
            usedToday.set(0);
        }
        if (usedToday.get() >= dailyBudget) {
            return false;
        }
        usedToday.incrementAndGet();
        return true;
    }
}
