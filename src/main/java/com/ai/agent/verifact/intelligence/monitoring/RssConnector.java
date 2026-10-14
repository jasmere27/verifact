package com.ai.agent.verifact.intelligence.monitoring;

import com.ai.agent.verifact.fetch.FetchFailedException;
import com.ai.agent.verifact.fetch.SafeUrlFetcher;
import com.ai.agent.verifact.fetch.UnsafeUrlException;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.parser.Parser;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Public RSS 2.0 / Atom feeds supplied by the customer (e.g. a publisher's or regulator's news feed), fetched
 * through {@link SafeUrlFetcher} (SSRF checks on every redirect). Only headline, summary text and link are kept.
 * Aggregator feeds whose terms forbid commercial use (e.g. Google News RSS) must not be added: see INTEGRATIONS.md.
 */
@Component
public class RssConnector implements SourceConnector {

    static final int MAX_ITEMS_PER_FEED = 50;

    private final SafeUrlFetcher fetcher;

    public RssConnector(SafeUrlFetcher fetcher) {
        this.fetcher = fetcher;
    }

    @Override
    public String id() {
        return "RSS";
    }

    @Override
    public boolean enabledFor(MonitoringConfig config) {
        return !config.sources().rssFeeds().isEmpty();
    }

    @Override
    public List<Item> fetch(Request request) {
        List<Item> items = new ArrayList<>();
        List<String> failed = new ArrayList<>();
        for (String feed : request.config().sources().rssFeeds()) {
            try {
                items.addAll(parse(fetcher.fetchFeed(feed).body()));
            } catch (UnsafeUrlException | FetchFailedException e) {
                failed.add(host(feed) + ": " + e.getMessage());
            }
        }
        if (!failed.isEmpty() && failed.size() == request.config().sources().rssFeeds().size()) {
            throw new ConnectorException(String.join("; ", failed));
        }
        if (!failed.isEmpty()) {
            throw new PartialResult(items, String.join("; ", failed));
        }
        return items;
    }

    static List<Item> parse(String xml) {
        Document doc = Jsoup.parse(xml, "", Parser.xmlParser());
        List<Item> items = new ArrayList<>();
        for (Element e : doc.select("item")) {               // RSS 2.0
            add(items, text(e, "link"), text(e, "title"), text(e, "description"), text(e, "pubDate"));
        }
        for (Element e : doc.select("entry")) {              // Atom
            Element link = e.selectFirst("link[rel=alternate], link:not([rel])");
            String href = link == null ? null : link.attr("href");
            add(items, href, text(e, "title"), firstNonBlank(text(e, "summary"), text(e, "content")),
                    firstNonBlank(text(e, "published"), text(e, "updated")));
        }
        return items.size() > MAX_ITEMS_PER_FEED ? items.subList(0, MAX_ITEMS_PER_FEED) : items;
    }

    private static void add(List<Item> items, String url, String title, String summary, String date) {
        if (url == null || url.isBlank() || !(url.startsWith("http://") || url.startsWith("https://"))) {
            return;
        }
        // Summaries are often HTML: keep the text only.
        String text = summary == null ? "" : Jsoup.parse(summary).text();
        items.add(new Item(url.trim(), title == null ? "" : Jsoup.parse(title).text(), text, date));
    }

    private static String text(Element parent, String tag) {
        Element e = parent.selectFirst(tag);
        return e == null ? null : e.text();
    }

    private static String firstNonBlank(String a, String b) {
        return a != null && !a.isBlank() ? a : b;
    }

    static String host(String url) {
        try {
            String h = java.net.URI.create(url).getHost();
            return h == null ? "feed" : h;
        } catch (IllegalArgumentException e) {
            return "feed";
        }
    }
}
