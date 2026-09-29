package com.ai.agent.verifact.verification;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Coarse, explainable category of a source, derived from its URL by fixed lists (no model
 * involvement). Used to show trust cues, order sources, and keep social media from counting as
 * independent evidence. Declaration order = display priority.
 */
public enum SourceType {
    FACT_CHECKER,
    GOVERNMENT,
    ACADEMIC,
    REFERENCE,
    NEWS,
    OTHER,
    /** Social media, forums, video and user-generated content: lower reliability. */
    SOCIAL;

    private static final Set<String> FACT_CHECKERS = Set.of(
            "snopes.com", "factcheck.org", "politifact.com", "fullfact.org", "verafiles.org", "tsek.ph",
            "leadstories.com", "checkyourfact.com", "healthfeedback.org", "sciencefeedback.co", "boomlive.in",
            "africacheck.org", "truthorfiction.com", "mediabiasfactcheck.com");
    /** Host+path prefixes for fact-check desks inside larger outlets. */
    private static final List<String> FACT_CHECK_PATHS = List.of(
            "factcheck.afp.com", "reuters.com/fact-check", "apnews.com/ap-fact-check", "apnews.com/hub/ap-fact-check",
            "rappler.com/newsbreak/fact-check", "usatoday.com/story/news/factcheck", "bbc.com/news/reality_check",
            "bbc.co.uk/news/reality_check", "fullfact.org");

    private static final Set<String> REFERENCE_SITES = Set.of(
            "wikipedia.org", "britannica.com", "merriam-webster.com", "worldatlas.com", "history.com",
            "nationalgeographic.com", "smithsonianmag.com", "si.edu", "khanacademy.org");

    private static final Set<String> ACADEMIC_SITES = Set.of(
            "nature.com", "sciencedirect.com", "springer.com", "jstor.org", "arxiv.org", "thelancet.com",
            "nejm.org", "bmj.com", "science.org", "cell.com", "pnas.org", "plos.org", "wiley.com",
            "researchgate.net", "scholar.google.com", "mayoclinic.org", "clevelandclinic.org", "hopkinsmedicine.org");

    private static final Set<String> NEWS_SITES = Set.of(
            "reuters.com", "apnews.com", "bbc.com", "bbc.co.uk", "nytimes.com", "theguardian.com",
            "washingtonpost.com", "cnn.com", "aljazeera.com", "npr.org", "abc.net.au", "bloomberg.com", "ft.com",
            "economist.com", "time.com", "nbcnews.com", "cbsnews.com", "abcnews.go.com", "usatoday.com",
            "independent.co.uk", "dw.com", "france24.com", "straitstimes.com", "scmp.com", "cnbc.com",
            "latimes.com", "wsj.com", "theatlantic.com", "newsweek.com", "axios.com", "politico.com",
            "inquirer.net", "philstar.com", "gmanetwork.com", "abs-cbn.com", "rappler.com", "mb.com.ph",
            "sunstar.com.ph", "manilatimes.net", "bworldonline.com", "cnnphilippines.com", "news.tv5.com.ph",
            "onenews.ph", "pna.gov.ph", "nikkei.com", "japantimes.co.jp", "channelnewsasia.com", "theconversation.com",
            "sciencenews.org", "livescience.com", "scientificamerican.com", "newscientist.com");

    private static final Set<String> SOCIAL_SITES = Set.of(
            "facebook.com", "fb.com", "fb.watch", "instagram.com", "x.com", "twitter.com", "tiktok.com",
            "reddit.com", "quora.com", "youtube.com", "youtu.be", "pinterest.com", "threads.net", "linkedin.com",
            "tumblr.com", "medium.com", "substack.com", "blogspot.com", "wordpress.com", "weebly.com", "wix.com",
            "answers.com", "stackexchange.com", "4chan.org", "9gag.com", "telegram.me", "t.me", "vk.com",
            "rumble.com", "bitchute.com", "gab.com", "truthsocial.com", "discord.com", "snapchat.com");

    /**
     * @param url  the source URL
     * @param site its registrable domain (e.g. "bbc.co.uk"); host subdomains are checked too
     */
    public static SourceType classify(String url, String host, String site) {
        String h = host == null ? "" : host.toLowerCase(Locale.ROOT);
        String s = site == null ? h : site.toLowerCase(Locale.ROOT);
        String hostAndPath = hostAndPath(url);

        for (String prefix : FACT_CHECK_PATHS) {
            if (hostAndPath.startsWith(prefix)) {
                return FACT_CHECKER;
            }
        }
        if (FACT_CHECKERS.contains(s)) {
            return FACT_CHECKER;
        }
        if (SOCIAL_SITES.contains(s) || matchesSuffix(h, SOCIAL_SITES)) {
            return SOCIAL;
        }
        if (isGovernment(h)) {
            return GOVERNMENT;
        }
        if (isAcademic(h) || ACADEMIC_SITES.contains(s)) {
            return ACADEMIC;
        }
        if (REFERENCE_SITES.contains(s)) {
            return REFERENCE;
        }
        if (NEWS_SITES.contains(s) || NEWS_SITES.contains(h)) {
            return NEWS;
        }
        return OTHER;
    }

    private static boolean isGovernment(String h) {
        return h.endsWith(".gov") || h.contains(".gov.") || h.endsWith(".mil") || h.endsWith(".int")
                || h.equals("un.org") || h.endsWith(".un.org") || h.endsWith("europa.eu");
    }

    private static boolean isAcademic(String h) {
        return h.endsWith(".edu") || h.contains(".edu.") || h.contains(".ac.");
    }

    private static boolean matchesSuffix(String host, Set<String> sites) {
        for (String site : sites) {
            if (host.equals(site) || host.endsWith("." + site)) {
                return true;
            }
        }
        return false;
    }

    private static String hostAndPath(String url) {
        if (url == null) {
            return "";
        }
        String u = url.trim().toLowerCase(Locale.ROOT).replaceFirst("^https?://", "");
        return u.startsWith("www.") ? u.substring(4) : u;
    }
}
