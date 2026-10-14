package com.ai.agent.verifact.intelligence.monitoring;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Relevance, query planning, URL de-duplication keys, feed parsing and page excerpts: all decided in code. */
class MonitoringRulesTest {

    static final MonitoringConfig CONFIG = new MonitoringConfig(List.of("Acme"), List.of("CrunchBar"), List.of("Jane Roe"),
            List.of("recall"), List.of("salmonella"), List.of("Acme Anvils"), new MonitoringConfig.Sources(true, true, List.of(), List.of()), 7, "HIGH");

    @Test
    void relevanceNeedsTheBrandAsAWholeWordAndRespectsExclusions() {
        assertThat(Relevance.evaluate("Acme Foods", CONFIG, "Acme Foods recalls CrunchBar", "Salmonella found.").level()).isEqualTo(Relevance.Level.HIGH);
        assertThat(Relevance.evaluate("Acme Foods", CONFIG, "CEO Jane Roe interviewed", "").level()).isEqualTo(Relevance.Level.MEDIUM);
        assertThat(Relevance.evaluate("Acme Foods", CONFIG, "Market news", "Shares of Acme rose.").level()).isEqualTo(Relevance.Level.LOW);
        assertThat(Relevance.evaluate("Acme Foods", CONFIG, "Acmeville fair", "Acmes everywhere")).as("whole words only").isNull();
        assertThat(Relevance.evaluate("Acme Foods", CONFIG, "Acme Anvils recall", "Acme Foods unaffected")).as("excluded").isNull();
        assertThat(Relevance.evaluate("Café Nero", new MonitoringConfig(null, null, null, null, null, null, null, 7, "HIGH"),
                "CAFE NERO opens", "")).as("accent and case insensitive").isNotNull();
    }

    @Test
    void queriesAreBoundedAndPhrasesQuoted() {
        List<String> q = QueryPlanner.queries("Acme Foods", CONFIG, 4);
        assertThat(q).hasSize(4).first().isEqualTo("\"Acme Foods\"");
        assertThat(q).contains("\"Acme Foods\" recall");
        MonitoringConfig injected = new MonitoringConfig(null, null, null, List.of("site:evil.example -legit"), null, null, null, 7, "HIGH");
        assertThat(QueryPlanner.queries("Acme", injected, 5)).allSatisfy(s -> assertThat(s).doesNotContain("site:").doesNotContain("-legit"));
    }

    @Test
    void urlKeysIgnoreTrackingParametersButKeepPageVersions() {
        assertThat(MentionStore.urlKey("https://www.news.example/a/?utm_source=x&id=7&fbclid=1"))
                .isEqualTo(MentionStore.urlKey("https://news.example/a?id=7"));
        assertThat(MentionStore.urlKey("https://shop.example/reviews#vf-abc123")).isNotEqualTo(MentionStore.urlKey("https://shop.example/reviews#vf-def456"));
        assertThat(MentionStore.urlKey("javascript:alert(1)")).isNull();
        assertThat(MentionStore.contentHash("Title", "Same text.")).isEqualTo(MentionStore.contentHash("title", "same  text"));
    }

    @Test
    void rssAndAtomItemsAreParsedAsTextOnly() {
        String rss = "<rss><channel><item><title>Acme recall</title><link>https://n.example/1</link>"
                + "<description>&lt;p&gt;Acme &lt;b&gt;recalls&lt;/b&gt; bars&lt;/p&gt;</description><pubDate>Mon, 12 Oct 2026 10:00:00 GMT</pubDate></item>"
                + "<item><title>Bad link</title><link>javascript:alert(1)</link></item></channel></rss>";
        List<SourceConnector.Item> items = RssConnector.parse(rss);
        assertThat(items).hasSize(1);
        assertThat(items.getFirst().snippet()).isEqualTo("Acme recalls bars");
        String atom = "<feed><entry><title>Acme update</title><link rel=\"alternate\" href=\"https://a.example/2\"/>"
                + "<summary>Acme statement</summary><published>2026-10-12T10:00:00Z</published></entry></feed>";
        assertThat(RssConnector.parse(atom)).extracting(SourceConnector.Item::url).containsExactly("https://a.example/2");
    }

    @Test
    void pageExcerptsAreThePagesOwnWords() {
        String text = "Welcome. Customers say Acme Foods ignored complaints about stale bars. Other news follows here.";
        String passages = PageWatchConnector.passages(text, List.of("acme foods"));
        assertThat(passages).contains("Customers say Acme Foods ignored complaints");
        assertThat(PageWatchConnector.passages("Nothing relevant", List.of("Acme"))).isNull();
    }

    @Test
    void incidentThresholds() {
        assertThat(MonitoringRunner.meets("HIGH", "HIGH")).isTrue();
        assertThat(MonitoringRunner.meets("MEDIUM", "HIGH")).isFalse();
        assertThat(MonitoringRunner.meets("MEDIUM", "MEDIUM")).isTrue();
        assertThat(MonitoringRunner.meets("HIGH", "NONE")).isFalse();
    }
}
