package com.ai.agent.verifact.intelligence.monitoring;

import com.ai.agent.verifact.fetch.FetchFailedException;
import com.ai.agent.verifact.fetch.SafeUrlFetcher;
import com.ai.agent.verifact.fetch.UnsafeUrlException;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Public web pages the customer asks us to watch (a review page, a forum thread, a competitor's statement). Each
 * run fetches the page; when it mentions the brand, the passages around the brand become an item keyed by the
 * page's content hash, so an unchanged page de-duplicates and each changed version is a new item. Login-protected pages can't be fetched and are reported as failures, never bypassed.
 */
@Component
public class PageWatchConnector implements SourceConnector {

    static final int CONTEXT_CHARS = 280;
    static final int MAX_PASSAGES = 3;

    private final SafeUrlFetcher fetcher;

    public PageWatchConnector(SafeUrlFetcher fetcher) {
        this.fetcher = fetcher;
    }

    @Override
    public String id() {
        return "PAGE_WATCH";
    }

    @Override
    public boolean enabledFor(MonitoringConfig config) {
        return !config.sources().pages().isEmpty();
    }

    @Override
    public List<Item> fetch(Request request) {
        List<Item> items = new ArrayList<>();
        List<String> failed = new ArrayList<>();
        for (String url : request.config().sources().pages()) {
            try {
                SafeUrlFetcher.FetchedPage page = fetcher.fetch(url);
                String hash = hash(page.text());
                String passages = passages(page.text(), request.config().entityTerms(request.brand()));
                if (passages != null) {
                    // The hash makes each changed version of the page a distinct item for de-duplication.
                    items.add(new Item(page.url() + "#vf-" + hash.substring(0, 12), page.title(), passages, null));
                }
            } catch (UnsafeUrlException | FetchFailedException e) {
                failed.add(RssConnector.host(url) + ": " + e.getMessage());
            }
        }
        if (!failed.isEmpty() && failed.size() == request.config().sources().pages().size()) {
            throw new ConnectorException(String.join("; ", failed));
        }
        if (!failed.isEmpty()) {
            throw new PartialResult(items, String.join("; ", failed));
        }
        return items;
    }

    /** Up to three excerpts (the page's own words) around entity-term matches, or null when the page doesn't mention the brand. */
    static String passages(String text, List<String> terms) {
        List<String> out = new ArrayList<>();
        int lastEnd = -1;
        for (String term : terms) {
            if (term == null || term.isBlank()) {
                continue;
            }
            Matcher m = Pattern.compile("(?<![\\p{L}\\p{N}])" + Pattern.quote(term.trim())
                    + "(?![\\p{L}\\p{N}])", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE).matcher(text);
            while (m.find() && out.size() < MAX_PASSAGES) {
                if (m.start() > lastEnd) {
                    int start = Math.max(0, m.start() - CONTEXT_CHARS / 2);
                    int end = Math.min(text.length(), m.end() + CONTEXT_CHARS / 2);
                    out.add((start > 0 ? "…" : "") + text.substring(start, end).trim() + (end < text.length() ? "…" : ""));
                    lastEnd = end;
                }
            }
        }
        return out.isEmpty() ? null : String.join(" ", out);
    }

    static String hash(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
