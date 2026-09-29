package com.ai.agent.verifact.fetch;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.InetAddress;
import java.net.UnknownHostException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UrlGuardTest {

    /** Resolves literal IPs normally and maps a few fake host names, so tests need no real DNS. */
    private static final UrlGuard GUARD = new UrlGuard(host -> switch (host) {
        case "public.example" -> new InetAddress[]{InetAddress.getByName("93.184.216.34")};
        case "internal.example" -> new InetAddress[]{InetAddress.getByName("10.1.2.3")};
        case "mixed.example" -> new InetAddress[]{
                InetAddress.getByName("93.184.216.34"), InetAddress.getByName("127.0.0.1")};
        case "localhost" -> new InetAddress[]{InetAddress.getByName("127.0.0.1")};
        case "nxdomain.example" -> throw new UnknownHostException(host);
        default -> InetAddress.getAllByName(host);
    });

    @ParameterizedTest
    @ValueSource(strings = {
            "http://public.example/article",
            "https://public.example:443/a?b=c",
            "http://93.184.216.34/",
            "https://[2606:4700:4700::1111]/",
    })
    void allowsPublicHttpUrls(String url) {
        assertThat(GUARD.validate(url)).isNotNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "http://localhost/",
            "http://127.0.0.1/",
            "http://127.1.2.3/",
            "http://0.0.0.0/",
            "http://10.0.0.1/",
            "http://172.16.5.4/",
            "http://192.168.1.1/",
            "http://169.254.169.254/latest/meta-data/",
            "http://100.64.0.1/",
            "http://[::1]/",
            "http://[::ffff:127.0.0.1]/",
            "http://[::ffff:169.254.169.254]/",
            "http://[fd00::1]/",
            "http://[fe80::1]/",
            "http://[64:ff9b::a9fe:a9fe]/",
            "http://internal.example/",
            "http://mixed.example/",
            "http://224.0.0.1/",
    })
    void blocksInternalAddresses(String url) {
        assertThatThrownBy(() -> GUARD.validate(url))
                .isInstanceOf(UnsafeUrlException.class)
                .hasMessageContaining("private or internal");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "file:///etc/passwd",
            "ftp://public.example/",
            "gopher://public.example/",
            "jar:http://public.example/x.jar!/",
            "javascript:alert(1)",
            "http://public.example:8080/",
            "http://public.example:22/",
            "http://user:pass@public.example/",
            "http:///no-host",
            "http://nxdomain.example/",
            "http://exa mple.com/",
    })
    void rejectsUnsupportedOrMalformedUrls(String url) {
        assertThatThrownBy(() -> GUARD.validate(url)).isInstanceOf(UnsafeUrlException.class);
    }

    @Test
    void looksLikeUrlOnlyMatchesSingleHttpTokens() {
        assertThat(UrlGuard.looksLikeUrl("https://bbc.com/news/x")).isTrue();
        assertThat(UrlGuard.looksLikeUrl("  HTTP://bbc.com  ")).isTrue();
        assertThat(UrlGuard.looksLikeUrl("The moon landing was faked")).isFalse();
        assertThat(UrlGuard.looksLikeUrl("see https://bbc.com for details")).isFalse();
        assertThat(UrlGuard.looksLikeUrl("file:///etc/passwd")).isFalse();
        assertThat(UrlGuard.looksLikeUrl(null)).isFalse();
    }
}
