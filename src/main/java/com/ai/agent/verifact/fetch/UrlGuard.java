package com.ai.agent.verifact.fetch;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.Locale;
import java.util.Set;

/**
 * Decides whether the server may fetch a URL. Only http(s) on default ports to hosts that
 * resolve exclusively to public addresses are allowed, which blocks requests to localhost,
 * private networks, and cloud metadata endpoints (SSRF).
 *
 * <p>Residual risk: DNS is resolved again by the HTTP client when connecting, so a hostile
 * DNS server could answer differently the second time (DNS rebinding). Closing that gap
 * needs an HTTP client with a pluggable resolver; tracked in known-issues.
 */
public class UrlGuard {

    private static final Set<String> ALLOWED_SCHEMES = Set.of("http", "https");

    /** Resolves host names; overridable in tests. */
    public interface Resolver {
        InetAddress[] resolve(String host) throws UnknownHostException;
    }

    private final Resolver resolver;

    public UrlGuard() {
        this(InetAddress::getAllByName);
    }

    public UrlGuard(Resolver resolver) {
        this.resolver = resolver;
    }

    /** Cheap syntactic check used to decide whether user input should be treated as a link. */
    public static boolean looksLikeUrl(String input) {
        if (input == null) {
            return false;
        }
        String trimmed = input.trim();
        if (trimmed.isEmpty() || trimmed.chars().anyMatch(Character::isWhitespace)) {
            return false;
        }
        String lower = trimmed.toLowerCase(Locale.ROOT);
        return lower.startsWith("http://") || lower.startsWith("https://");
    }

    /**
     * @return the parsed URI if it is safe to fetch
     * @throws UnsafeUrlException if it is not
     */
    public URI validate(String url) {
        URI uri;
        try {
            uri = new URI(url.trim());
        } catch (URISyntaxException e) {
            throw new UnsafeUrlException("Malformed URL");
        }

        String scheme = uri.getScheme() == null ? null : uri.getScheme().toLowerCase(Locale.ROOT);
        if (scheme == null || !ALLOWED_SCHEMES.contains(scheme)) {
            throw new UnsafeUrlException("Only http and https links are supported");
        }
        if (uri.getRawUserInfo() != null) {
            throw new UnsafeUrlException("Links with embedded credentials are not supported");
        }
        int port = uri.getPort();
        if (port != -1 && port != 80 && port != 443) {
            throw new UnsafeUrlException("Links to non-standard ports are not supported");
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new UnsafeUrlException("Link has no host");
        }

        InetAddress[] addresses;
        try {
            addresses = resolver.resolve(host);
        } catch (UnknownHostException e) {
            throw new UnsafeUrlException("Host could not be resolved");
        }
        if (addresses.length == 0) {
            throw new UnsafeUrlException("Host could not be resolved");
        }
        for (InetAddress address : addresses) {
            if (!isPublic(address)) {
                throw new UnsafeUrlException("Links to private or internal addresses are not allowed");
            }
        }
        return uri;
    }

    static boolean isPublic(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()) {
            return false;
        }
        byte[] b = address.getAddress();
        if (address instanceof Inet4Address) {
            return isPublicIpv4(b);
        }
        if (address instanceof Inet6Address) {
            int first = b[0] & 0xff;
            if ((first & 0xfe) == 0xfc) {
                return false; // fc00::/7 unique local
            }
            if (first == 0xfe && (b[1] & 0xc0) == 0xc0) {
                return false; // fec0::/10 deprecated site local
            }
            // IPv4-mapped (::ffff:a.b.c.d), IPv4-compatible (::a.b.c.d) and NAT64 (64:ff9b::/96)
            // embed an IPv4 address in the last 4 bytes; judge that address instead.
            boolean mapped = allZero(b, 0, 10) && (b[10] & 0xff) == 0xff && (b[11] & 0xff) == 0xff;
            boolean compatible = allZero(b, 0, 12);
            boolean nat64 = (b[0] & 0xff) == 0x00 && (b[1] & 0xff) == 0x64 && (b[2] & 0xff) == 0xff
                    && (b[3] & 0xff) == 0x9b && allZero(b, 4, 12);
            if (mapped || compatible || nat64) {
                return isPublicIpv4(new byte[]{b[12], b[13], b[14], b[15]});
            }
            return true;
        }
        return false;
    }

    private static boolean isPublicIpv4(byte[] b) {
        int a0 = b[0] & 0xff;
        int a1 = b[1] & 0xff;
        int a2 = b[2] & 0xff;
        return !(a0 == 0                                   // 0.0.0.0/8 "this network"
                || a0 == 10                                // 10.0.0.0/8
                || a0 == 127                               // loopback
                || (a0 == 100 && a1 >= 64 && a1 <= 127)    // 100.64.0.0/10 CGNAT
                || (a0 == 169 && a1 == 254)                // link local incl. cloud metadata
                || (a0 == 172 && a1 >= 16 && a1 <= 31)     // 172.16.0.0/12
                || (a0 == 192 && a1 == 0 && a2 == 0)       // 192.0.0.0/24 IETF
                || (a0 == 192 && a1 == 168)                // 192.168.0.0/16
                || (a0 == 198 && (a1 == 18 || a1 == 19))   // 198.18.0.0/15 benchmarking
                || a0 >= 224);                             // multicast, reserved, broadcast
    }

    private static boolean allZero(byte[] b, int from, int to) {
        for (int i = from; i < to; i++) {
            if (b[i] != 0) {
                return false;
            }
        }
        return true;
    }
}
