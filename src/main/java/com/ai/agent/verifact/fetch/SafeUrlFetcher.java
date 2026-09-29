package com.ai.agent.verifact.fetch;

import org.jsoup.Connection;
import org.jsoup.Jsoup;
import org.jsoup.UnsupportedMimeTypeException;
import org.jsoup.nodes.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;

/**
 * Fetches a web page for analysis. Every hop (including redirects) is validated by
 * {@link UrlGuard}; response size and time are capped; only HTML/text is accepted.
 * Content returned is untrusted and must be treated as data, never as instructions.
 */
@Component
public class SafeUrlFetcher {

    private static final Logger log = LoggerFactory.getLogger(SafeUrlFetcher.class);
    private static final int MAX_REDIRECTS = 3;
    private static final String USER_AGENT = "Mozilla/5.0 (compatible; VeriFactBot/1.0)";

    public record FetchedPage(String url, String title, String text) {}

    private final UrlGuard urlGuard;
    private final int timeoutMillis;
    private final int maxBodyBytes;

    @Autowired
    public SafeUrlFetcher(@Value("${app.fetch.timeout-millis:10000}") int timeoutMillis,
                          @Value("${app.fetch.max-body-bytes:2000000}") int maxBodyBytes) {
        this(new UrlGuard(), timeoutMillis, maxBodyBytes);
    }

    SafeUrlFetcher(UrlGuard urlGuard, int timeoutMillis, int maxBodyBytes) {
        this.urlGuard = urlGuard;
        this.timeoutMillis = timeoutMillis;
        this.maxBodyBytes = maxBodyBytes;
    }

    public FetchedPage fetch(String url) {
        URI current = urlGuard.validate(url);

        for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
            Connection.Response response;
            try {
                response = Jsoup.connect(current.toString())
                        .userAgent(USER_AGENT)
                        .followRedirects(false)
                        .ignoreHttpErrors(true)
                        .maxBodySize(maxBodyBytes)
                        .timeout(timeoutMillis)
                        .execute();
            } catch (UnsupportedMimeTypeException e) {
                throw new FetchFailedException("That link isn't a web page VeriFact can read.");
            } catch (IOException e) {
                log.info("Fetch failed for host {}: {}", current.getHost(), e.getClass().getSimpleName());
                throw new FetchFailedException("VeriFact couldn't reach that link.", e);
            }

            int status = response.statusCode();
            if (status >= 300 && status < 400) {
                String location = response.header("Location");
                if (location == null || location.isBlank()) {
                    throw new FetchFailedException("That link redirects somewhere VeriFact can't follow.");
                }
                current = urlGuard.validate(current.resolve(location).toString());
                continue;
            }
            if (status != 200) {
                throw new FetchFailedException("That link returned an error (HTTP " + status + ").");
            }

            try {
                Document document = response.parse();
                String text = document.body() == null ? "" : document.body().text();
                if (text.isBlank()) {
                    throw new FetchFailedException("That page has no readable text.");
                }
                return new FetchedPage(current.toString(), document.title(), text);
            } catch (IOException e) {
                throw new FetchFailedException("VeriFact couldn't read that page.", e);
            }
        }
        throw new FetchFailedException("That link redirects too many times.");
    }
}
