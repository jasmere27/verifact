package com.ai.agent.verifact.evidence;

import com.google.common.net.InternetDomainName;

import java.net.URI;
import java.util.Locale;

/** URL helpers for evidence: hosts, owning sites, and a normal form for de-duplication. */
public final class Urls {

    private Urls() {
    }

    /** Lower-cased host without a leading "www.", or null if the URL has none. */
    public static String domain(String url) {
        try {
            String host = URI.create(url.trim()).getHost();
            if (host == null) {
                return null;
            }
            host = host.toLowerCase(Locale.ROOT);
            return host.startsWith("www.") ? host.substring(4) : host;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * The site that owns a host, per the public suffix list: news.bbc.co.uk → bbc.co.uk. Hosts on
     * shared-hosting suffixes (e.g. blogspot.com) stay distinct per site. IPs and unknowns pass through.
     */
    public static String registrableDomain(String host) {
        if (host == null) {
            return null;
        }
        try {
            InternetDomainName name = InternetDomainName.from(host);
            if (name.isUnderPublicSuffix()) {
                return name.topPrivateDomain().toString();
            }
        } catch (IllegalArgumentException | IllegalStateException e) {
            // IP literal or invalid name: fall through
        }
        return host;
    }

    /** host + path + query for http(s) links (so trivially different URLs de-duplicate); null otherwise. */
    public static String normalizeUrl(String url) {
        if (url == null || url.isBlank()) {
            return null;
        }
        try {
            URI uri = URI.create(url.trim());
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            if (!scheme.equals("http") && !scheme.equals("https")) {
                return null; // only web links can be shown as sources
            }
            String path = uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/" : uri.getRawPath();
            if (path.length() > 1 && path.endsWith("/")) {
                path = path.substring(0, path.length() - 1);
            }
            String query = uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery();
            return domain(url) + path + query;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** True if {@code host} is {@code allowed} or one of its subdomains (e.g. www.dol.gov for dol.gov). */
    public static boolean isOnDomain(String host, String allowed) {
        return host != null && (host.equals(allowed) || host.endsWith("." + allowed));
    }
}
