package com.ai.agent.verifact.fetch;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Runs against a local HTTP server. The real guard would (correctly) refuse localhost, so the
 * test guard lets only the local test server through and applies the real rules to everything else,
 * which is exactly what we need to prove redirects are re-validated.
 */
class SafeUrlFetcherTest {

    private HttpServer server;
    private String base;
    private SafeUrlFetcher fetcher;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/page", exchange -> respond(exchange, 200, "text/html",
                "<html><head><title>Headline</title></head><body><p>Body text here.</p></body></html>"));
        server.createContext("/empty", exchange -> respond(exchange, 200, "text/html", "<html><body></body></html>"));
        server.createContext("/binary", exchange -> respond(exchange, 200, "application/octet-stream", "xx"));
        server.createContext("/missing", exchange -> respond(exchange, 404, "text/html", "nope"));
        server.createContext("/to-metadata", exchange -> {
            exchange.getResponseHeaders().add("Location", "http://169.254.169.254/latest/meta-data/");
            respond(exchange, 302, "text/html", "");
        });
        server.createContext("/to-page", exchange -> {
            exchange.getResponseHeaders().add("Location", "/page");
            respond(exchange, 301, "text/html", "");
        });
        server.createContext("/bad-location", exchange -> {
            exchange.getResponseHeaders().add("Location", "http://exa mple.com/%zz");
            respond(exchange, 302, "text/html", "");
        });
        server.createContext("/loop", exchange -> {
            exchange.getResponseHeaders().add("Location", "/loop");
            respond(exchange, 302, "text/html", "");
        });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();

        UrlGuard real = new UrlGuard();
        UrlGuard testGuard = new UrlGuard() {
            @Override
            public URI validate(String url) {
                return url.startsWith(base) ? URI.create(url) : real.validate(url);
            }
        };
        fetcher = new SafeUrlFetcher(testGuard, 5000, 100_000);
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void fetchesTitleAndBodyText() {
        SafeUrlFetcher.FetchedPage page = fetcher.fetch(base + "/page");
        assertThat(page.title()).isEqualTo("Headline");
        assertThat(page.text()).contains("Body text here.");
    }

    @Test
    void followsSafeRedirects() {
        assertThat(fetcher.fetch(base + "/to-page").title()).isEqualTo("Headline");
    }

    @Test
    void revalidatesRedirectTargets() {
        assertThatThrownBy(() -> fetcher.fetch(base + "/to-metadata")).isInstanceOf(UnsafeUrlException.class);
    }

    @Test
    void malformedRedirectIsAFetchFailureNot500() {
        assertThatThrownBy(() -> fetcher.fetch(base + "/bad-location")).isInstanceOf(FetchFailedException.class);
    }

    @Test
    void capsRedirectChains() {
        assertThatThrownBy(() -> fetcher.fetch(base + "/loop"))
                .isInstanceOf(FetchFailedException.class)
                .hasMessageContaining("too many");
    }

    @Test
    void rejectsNonTextContent() {
        assertThatThrownBy(() -> fetcher.fetch(base + "/binary")).isInstanceOf(FetchFailedException.class);
    }

    @Test
    void reportsHttpErrors() {
        assertThatThrownBy(() -> fetcher.fetch(base + "/missing"))
                .isInstanceOf(FetchFailedException.class)
                .hasMessageContaining("404");
    }

    @Test
    void rejectsPagesWithoutText() {
        assertThatThrownBy(() -> fetcher.fetch(base + "/empty")).isInstanceOf(FetchFailedException.class);
    }

    @Test
    void productionGuardRefusesLocalServer() {
        SafeUrlFetcher production = new SafeUrlFetcher(5000, 100_000);
        assertThatThrownBy(() -> production.fetch(base + "/page")).isInstanceOf(UnsafeUrlException.class);
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, int status, String contentType,
                                String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", contentType);
        exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) {
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        }
        exchange.close();
    }
}
