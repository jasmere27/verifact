package com.ai.agent.verifact.intelligence.monitoring;

import com.ai.agent.verifact.video.FoundVideo;
import com.ai.agent.verifact.video.VideoSearch;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** YouTube monitoring: recent uploads only, metadata as the item text, a daily budget, and visible quota failures. */
class YouTubeConnectorTest {

    static final Instant NOW = Instant.parse("2026-10-14T00:00:00Z");
    static final MonitoringConfig CONFIG = new MonitoringConfig(null, null, null, null, null, null,
            new MonitoringConfig.Sources(false, false, List.of(), List.of(), true), 7, "HIGH");

    static final class FakeVideos implements VideoSearch {
        final List<Instant> after = new ArrayList<>();
        RuntimeException fail;

        @Override
        public boolean enabled() {
            return true;
        }

        @Override
        public List<FoundVideo> search(String query, int max) {
            throw new AssertionError("monitoring must use the date-limited search");
        }

        @Override
        public List<FoundVideo> search(String query, int max, Instant publishedAfter) {
            after.add(publishedAfter);
            if (fail != null) {
                throw fail;
            }
            return List.of(new FoundVideo("youtube", "aaaaaaaaaaa", "https://www.youtube.com/watch?v=aaaaaaaaaaa", "Acme Foods recall explained",
                    "Daily News", "UC1", Instant.parse("2026-10-12T09:00:00Z"), 300, "Is Acme Foods recalling peanut butter?\nWe look at the claims.",
                    null, true));
        }
    }

    private static SourceConnector.Request request() {
        return new SourceConnector.Request("Acme Foods", CONFIG, List.of("\"Acme Foods\"", "\"Acme Foods\" recall"));
    }

    @Test
    void searchesTheLookBackWindowAndUsesTitleChannelAndDescription() {
        FakeVideos videos = new FakeVideos();
        YouTubeConnector c = new YouTubeConnector(videos, Clock.fixed(NOW, ZoneOffset.UTC), 1, 10, 5);

        List<SourceConnector.Item> items = c.fetch(request());

        assertThat(videos.after).containsExactly(Instant.parse("2026-10-07T00:00:00Z")); // one query per run, 7-day look-back
        assertThat(items).singleElement().satisfies(i -> {
            assertThat(i.title()).isEqualTo("Acme Foods recall explained");
            assertThat(i.snippet()).isEqualTo("Channel: Daily News. Is Acme Foods recalling peanut butter? We look at the claims.");
            assertThat(i.publishedDate()).isEqualTo("2026-10-12");
        });
        assertThat(c.enabledFor(CONFIG)).isTrue();
    }

    @Test
    void theDailyBudgetIsKeptAndReportedWhenUsedUp() {
        YouTubeConnector c = new YouTubeConnector(new FakeVideos(), Clock.fixed(NOW, ZoneOffset.UTC), 1, 10, 2);
        c.fetch(request());
        c.fetch(request());
        assertThatThrownBy(() -> c.fetch(request())).isInstanceOf(SourceConnector.ConnectorException.class).hasMessageContaining("budget");
    }

    @Test
    void quotaErrorsFailTheSourceVisibly() {
        FakeVideos videos = new FakeVideos();
        videos.fail = new VideoSearch.VideoSearchException("YouTube returned HTTP 403", true);
        YouTubeConnector c = new YouTubeConnector(videos, Clock.fixed(NOW, ZoneOffset.UTC), 1, 10, 5);
        assertThatThrownBy(() -> c.fetch(request())).isInstanceOf(SourceConnector.ConnectorException.class).hasMessageContaining("quota");
    }

    @Test
    void projectsSavedBeforeYouTubeExistedStillLoad() {
        String old = "{\"aliases\":[],\"sources\":{\"webSearch\":true,\"newsSearch\":false,\"rssFeeds\":[],\"pages\":[]},\"lookbackDays\":7,\"alertThreshold\":\"HIGH\"}";
        MonitoringConfig c = JsonMapper.builder().build().readValue(old, MonitoringConfig.class);
        assertThat(c.sources().webSearch()).isTrue();
        assertThat(c.sources().youtube()).isFalse();
    }
}
