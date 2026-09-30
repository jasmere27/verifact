package com.ai.agent.verifact.research;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * {@link ScholarlyIndex} on Crossref (metadata, reference matching, retraction/correction notices)
 * and OpenAlex (retraction flag from Retraction Watch, abstracts, citation counts, related-work
 * search). Researched 2026-09-30 (see .claude/memory/researchfact.md): Crossref is free with a
 * polite pool for callers that send a contact email; OpenAlex single-work lookups are free and
 * searches cost $0.001 (an API key raises the free daily budget).
 */
@Component
public class CrossrefOpenAlexIndex implements ScholarlyIndex {

    private static final Logger log = LoggerFactory.getLogger(CrossrefOpenAlexIndex.class);

    static final String CROSSREF = "https://api.crossref.org";
    static final String OPENALEX = "https://api.openalex.org";
    static final String DATACITE = "https://api.datacite.org";
    static final String PUBMED_EFETCH = "https://eutils.ncbi.nlm.nih.gov/entrez/eutils/efetch.fcgi";
    private static final java.util.regex.Pattern ABSTRACT_TEXT = java.util.regex.Pattern.compile(
            "<AbstractText[^>]*>(.*?)</AbstractText>", java.util.regex.Pattern.DOTALL);

    private final RestClient http;
    private final JsonMapper jsonMapper;
    private final String contactEmail;
    private final String openAlexKey;

    public CrossrefOpenAlexIndex(RestClient.Builder builder, JsonMapper jsonMapper,
                                 @Value("${app.research.contact-email:}") String contactEmail,
                                 @Value("${app.research.openalex-api-key:}") String openAlexKey) {
        this.contactEmail = contactEmail == null ? "" : contactEmail.strip();
        this.openAlexKey = openAlexKey == null ? "" : openAlexKey.strip();
        this.http = builder
                .defaultHeader("User-Agent", "ResearchFact/1.0 (https://verifact-blf.pages.dev"
                        + (this.contactEmail.isEmpty() ? "" : "; mailto:" + this.contactEmail) + ")")
                .build();
        this.jsonMapper = jsonMapper;
    }

    @Override
    public Optional<ScholarlyWork> byDoi(String doi) {
        JsonNode crossref = get(URI.create(CROSSREF + "/works/" + encodeDoi(doi) + (contactEmail.isEmpty() ? ""
                : "?mailto=" + java.net.URLEncoder.encode(contactEmail, java.nio.charset.StandardCharsets.UTF_8))), true);
        if (crossref == null) {
            // Not a Crossref DOI: DataCite registers arXiv, Zenodo and dataset DOIs (with abstracts).
            JsonNode datacite = get(URI.create(DATACITE + "/dois/" + encodeDoi(doi)), true);
            if (datacite != null) {
                return Optional.of(fromDataCite(datacite.path("data").path("attributes")));
            }
            JsonNode w = get(URI.create(OPENALEX + "/works/doi:" + encodeDoi(doi.toLowerCase(java.util.Locale.ROOT))
                    + "?select=doi,display_name,authorships,publication_year,primary_location,cited_by_count,is_retracted,abstract_inverted_index"
                    + (openAlexKey.isEmpty() ? "" : "&api_key=" + java.net.URLEncoder.encode(openAlexKey, java.nio.charset.StandardCharsets.UTF_8))), true);
            return w == null ? Optional.empty() : Optional.of(fromOpenAlex(w));
        }
        ScholarlyWork work = fromCrossref(crossref.path("message"));
        return Optional.of(enrich(work));
    }

    @Override
    public List<ScholarlyWork> byReference(String referenceText, int rows) {
        URI uri = crossrefBuilder("/works")
                .queryParam("query.bibliographic", referenceText)
                .queryParam("rows", rows)
                .queryParam("select", "DOI,title,subtitle,author,issued,container-title,publisher,is-referenced-by-count,updated-by")
                .encode().build().toUri();
        throttle("crossref-list", contactEmail.isEmpty() ? 1100 : 350); // public pool 1 req/s, polite 3 req/s
        JsonNode root = get(uri, false);
        List<ScholarlyWork> out = new ArrayList<>();
        if (root != null) {
            for (JsonNode item : root.path("message").path("items")) {
                out.add(fromCrossref(item));
            }
        }
        return out;
    }

    @Override
    public List<ScholarlyWork> related(String query, int rows) {
        UriComponentsBuilder b = UriComponentsBuilder.fromUriString(OPENALEX + "/works")
                .queryParam("search", query)
                .queryParam("filter", "has_abstract:true")
                .queryParam("per-page", rows)
                .queryParam("select", "doi,display_name,authorships,publication_year,primary_location,cited_by_count,is_retracted,abstract_inverted_index");
        if (!openAlexKey.isEmpty()) {
            b.queryParam("api_key", openAlexKey);
        }
        JsonNode root = get(b.encode().build().toUri(), false);
        List<ScholarlyWork> out = new ArrayList<>();
        if (root != null) {
            for (JsonNode w : root.path("results")) {
                out.add(fromOpenAlex(w));
            }
        }
        return out;
    }

    @Override
    public Optional<DiscoveredWork> work(String key) {
        String path;
        if (key != null && key.matches("(?i)10\\.\\d{4,9}/\\S+")) {
            path = "/works/doi:" + encodeDoi(key.toLowerCase(java.util.Locale.ROOT));
        } else if (key != null && key.matches("https://openalex\\.org/W\\d{1,12}")) {
            path = "/works/" + key.substring("https://openalex.org/".length());
        } else {
            return Optional.empty();
        }
        JsonNode w = get(URI.create(OPENALEX + path + "?select=id,doi,display_name,authorships,publication_year,"
                + "primary_location,type,cited_by_count,is_retracted,abstract_inverted_index"
                + (openAlexKey.isEmpty() ? "" : "&api_key=" + java.net.URLEncoder.encode(openAlexKey, java.nio.charset.StandardCharsets.UTF_8))), true);
        return w == null ? Optional.empty() : Optional.of(discovered(w));
    }

    @Override
    public List<DiscoveredWork> discover(String query, String countryCode, Integer fromYear, String type,
                                         boolean byCitations, int rows) {
        List<String> filters = new ArrayList<>();
        if (countryCode != null && countryCode.matches("[A-Za-z]{2}")) {
            filters.add("authorships.institutions.country_code:" + countryCode.toLowerCase(java.util.Locale.ROOT));
        }
        if (fromYear != null) {
            filters.add("from_publication_date:" + fromYear + "-01-01");
        }
        if (type != null && type.matches("[a-z-]+")) {
            filters.add("type:" + type);
        }
        UriComponentsBuilder b = UriComponentsBuilder.fromUriString(OPENALEX + "/works")
                .queryParam("search", query)
                .queryParam("per-page", rows)
                .queryParam("select", "id,doi,display_name,authorships,publication_year,primary_location,type,"
                        + "cited_by_count,is_retracted,abstract_inverted_index");
        if (!filters.isEmpty()) {
            b.queryParam("filter", String.join(",", filters));
        }
        if (byCitations) {
            b.queryParam("sort", "cited_by_count:desc");
        }
        if (!openAlexKey.isEmpty()) {
            b.queryParam("api_key", openAlexKey);
        }
        JsonNode root = get(b.encode().build().toUri(), false);
        List<DiscoveredWork> out = new ArrayList<>();
        if (root != null) {
            for (JsonNode w : root.path("results")) {
                out.add(discovered(w));
            }
        }
        return out;
    }

    DiscoveredWork discovered(JsonNode w) {
        java.util.Set<String> countries = new java.util.TreeSet<>();
        for (JsonNode a : w.path("authorships")) {
            for (JsonNode inst : a.path("institutions")) {
                String c = inst.path("country_code").asString("");
                if (c.matches("[A-Za-z]{2}")) {
                    countries.add(c.toUpperCase(java.util.Locale.ROOT));
                }
            }
        }
        String landing = w.path("primary_location").path("landing_page_url").asString("");
        return new DiscoveredWork(fromOpenAlex(w), emptyToNull(w.path("id").asString("")),
                emptyToNull(w.path("type").asString("")), List.copyOf(countries), emptyToNull(landing));
    }

    /** Adds OpenAlex's retraction flag, abstract and citation count to a Crossref record. */
    private ScholarlyWork enrich(ScholarlyWork work) {
        if (work.doi() == null) {
            return work;
        }
        try {
            JsonNode w = get(URI.create(OPENALEX + "/works/doi:" + encodeDoi(work.doi())
                    + "?select=is_retracted,abstract_inverted_index,cited_by_count,primary_location,ids"
                    + (openAlexKey.isEmpty() ? "" : "&api_key=" + java.net.URLEncoder.encode(openAlexKey, java.nio.charset.StandardCharsets.UTF_8))), true);
            if (w == null) {
                return work;
            }
            String abs = abstractFrom(w.path("abstract_inverted_index"));
            String pmid = w.path("ids").path("pmid").asString("").replaceAll("\\D", "");
            if (abs == null && work.abstractText() == null && !pmid.isEmpty()) {
                abs = pubmedAbstract(pmid); // OpenAlex withholds many publishers' abstracts; PubMed often has them
            }
            return work.withIndexData(w.path("is_retracted").asBoolean(false), abs,
                    w.path("cited_by_count").isNumber() ? w.path("cited_by_count").asInt() : null,
                    emptyToNull(w.path("primary_location").path("source").path("display_name").asString("")));
        } catch (ScholarlyIndexUnavailableException e) {
            log.warn("OpenAlex lookup failed: {}", e.getMessage());
            return work; // Crossref already confirmed the work exists
        }
    }

    /** PubMed abstract for a PMID, or null. Failures here never fail the check. */
    private String pubmedAbstract(String pmid) {
        try {
            URI uri = UriComponentsBuilder.fromUriString(PUBMED_EFETCH).queryParam("db", "pubmed").queryParam("id", pmid)
                    .queryParam("rettype", "abstract").queryParam("retmode", "xml").build().toUri();
            throttle("pubmed", 350); // 3 req/s without an API key, shared by all users of this instance
            String xml = http.get().uri(uri).retrieve().body(String.class);
            return abstractFromPubmedXml(xml);
        } catch (RuntimeException e) {
            log.warn("PubMed abstract lookup failed: {}", e.getClass().getSimpleName());
            return null;
        }
    }

    static String abstractFromPubmedXml(String xml) {
        if (xml == null) {
            return null;
        }
        java.util.regex.Matcher m = ABSTRACT_TEXT.matcher(xml);
        List<String> parts = new ArrayList<>();
        while (m.find()) {
            parts.add(m.group(1).replaceAll("<[^>]+>", "").replace("&lt;", "<").replace("&gt;", ">")
                    .replace("&quot;", "\"").replace("&#39;", "'").replace("&amp;", "&").strip());
        }
        String text = String.join(" ", parts).replaceAll("\\s+", " ").strip();
        return text.isEmpty() ? null : text;
    }

    ScholarlyWork fromDataCite(JsonNode a) {
        List<String> authors = new ArrayList<>();
        for (JsonNode c : a.path("creators")) {
            String name = c.path("name").asString("");
            if (name.contains(",")) { // "Vaswani, Ashish" → "Ashish Vaswani"
                String[] p = name.split(",", 2);
                name = (p[1].strip() + " " + p[0].strip()).strip();
            }
            if (!name.isEmpty()) {
                authors.add(name);
            }
        }
        String abs = null;
        for (JsonNode d : a.path("descriptions")) {
            if ("Abstract".equalsIgnoreCase(d.path("descriptionType").asString(""))) {
                abs = d.path("description").asString("").replaceAll("\\s+", " ").strip();
            }
        }
        JsonNode publisher = a.path("publisher");
        String pub = publisher.isObject() ? publisher.path("name").asString("") : publisher.asString("");
        return new ScholarlyWork(emptyToNull(a.path("doi").asString("").toLowerCase(java.util.Locale.ROOT)),
                emptyToNull(a.path("titles").path(0).path("title").asString("")), authors,
                a.path("publicationYear").isNumber() ? a.path("publicationYear").asInt()
                        : a.path("publicationYear").asString("").matches("\\d{4}") ? Integer.parseInt(a.path("publicationYear").asString()) : null,
                emptyToNull(pub), emptyToNull(pub), null, false, List.of(), abs == null || abs.isEmpty() ? null : abs);
    }

    ScholarlyWork fromCrossref(JsonNode m) {
        List<String> authors = new ArrayList<>();
        for (JsonNode a : m.path("author")) {
            String name = (a.path("given").asString("") + " " + a.path("family").asString("")).strip();
            if (name.isEmpty()) {
                name = a.path("name").asString("");
            }
            if (!name.isEmpty()) {
                authors.add(name);
            }
        }
        JsonNode parts = m.path("issued").path("date-parts").path(0).path(0);
        List<String> notices = new ArrayList<>();
        boolean retracted = false;
        for (JsonNode u : m.path("updated-by")) {
            String type = u.path("type").asString("").toLowerCase(java.util.Locale.ROOT).replace('-', '_');
            if (!type.isEmpty() && !notices.contains(type)) {
                notices.add(type);
            }
            retracted |= type.equals("retraction") || type.equals("removal") || type.equals("withdrawal");
        }
        String abs = m.path("abstract").asString("");
        String title = m.path("title").path(0).asString("");
        String subtitle = m.path("subtitle").path(0).asString("");
        if (!title.isBlank() && !subtitle.isBlank()) {
            title = title + ": " + subtitle; // references usually cite "Title: Subtitle"
        }
        return new ScholarlyWork(
                emptyToNull(m.path("DOI").asString("").toLowerCase(java.util.Locale.ROOT)),
                emptyToNull(title),
                authors,
                parts.isNumber() ? parts.asInt() : null,
                emptyToNull(m.path("container-title").path(0).asString("")),
                emptyToNull(m.path("publisher").asString("")),
                m.path("is-referenced-by-count").isNumber() ? m.path("is-referenced-by-count").asInt() : null,
                retracted, notices,
                abs.isBlank() ? null : abs.replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ").strip());
    }

    ScholarlyWork fromOpenAlex(JsonNode w) {
        List<String> authors = new ArrayList<>();
        for (JsonNode a : w.path("authorships")) {
            String name = a.path("author").path("display_name").asString("");
            if (!name.isEmpty()) {
                authors.add(name);
            }
        }
        String doi = w.path("doi").asString("").replaceFirst("(?i)^https?://doi\\.org/", "").toLowerCase(java.util.Locale.ROOT);
        return new ScholarlyWork(emptyToNull(doi), emptyToNull(w.path("display_name").asString("")), authors,
                w.path("publication_year").isNumber() ? w.path("publication_year").asInt() : null,
                emptyToNull(w.path("primary_location").path("source").path("display_name").asString("")), null,
                w.path("cited_by_count").isNumber() ? w.path("cited_by_count").asInt() : null,
                w.path("is_retracted").asBoolean(false), List.of(), abstractFrom(w.path("abstract_inverted_index")));
    }

    /** OpenAlex ships abstracts only as {word: [positions]}; this puts the words back in order. */
    static String abstractFrom(JsonNode inverted) {
        if (inverted == null || !inverted.isObject() || inverted.isEmpty()) {
            return null;
        }
        Map<Integer, String> byPosition = new TreeMap<>();
        for (Map.Entry<String, JsonNode> e : inverted.properties()) {
            for (JsonNode p : e.getValue()) {
                byPosition.put(p.asInt(), e.getKey());
            }
        }
        return byPosition.isEmpty() ? null : String.join(" ", byPosition.values());
    }

    /** @return null on 404 when {@code notFoundIsEmpty}; throws on any other failure */
    private JsonNode get(URI uri, boolean notFoundIsEmpty) {
        try {
            String body = http.get().uri(uri).retrieve().body(String.class);
            return jsonMapper.readTree(body == null ? "{}" : body);
        } catch (RestClientResponseException e) {
            HttpStatusCode status = e.getStatusCode();
            if (notFoundIsEmpty && status.value() == 404) {
                return null;
            }
            throw new ScholarlyIndexUnavailableException(uri.getHost() + " returned HTTP " + status.value());
        } catch (RestClientException e) {
            throw new ScholarlyIndexUnavailableException(uri.getHost() + " request failed: " + e.getClass().getSimpleName());
        } catch (RuntimeException e) {
            throw new ScholarlyIndexUnavailableException(uri.getHost() + " returned unreadable data");
        }
    }

    private UriComponentsBuilder crossrefBuilder(String path) {
        UriComponentsBuilder b = UriComponentsBuilder.fromUriString(CROSSREF + path);
        if (!contactEmail.isEmpty()) {
            b.queryParam("mailto", contactEmail); // Crossref's polite pool
        }
        return b;
    }

    private static final Map<String, Long> LAST_CALL = new java.util.concurrent.ConcurrentHashMap<>();

    /** Spaces calls to a rate-limited endpoint across all requests on this instance. */
    private static void throttle(String key, long minIntervalMillis) {
        synchronized (LAST_CALL) {
            long now = System.currentTimeMillis();
            long wait = LAST_CALL.getOrDefault(key, 0L) + minIntervalMillis - now;
            if (wait > 0) {
                try {
                    Thread.sleep(wait);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            LAST_CALL.put(key, System.currentTimeMillis());
        }
    }

    /** DOIs contain '/', '<', '(' etc.; each part is percent-encoded but the prefix/suffix slash kept. */
    static String encodeDoi(String doi) {
        return java.net.URLEncoder.encode(doi, java.nio.charset.StandardCharsets.UTF_8).replace("+", "%20").replace("%2F", "/");
    }

    private static String emptyToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
