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
            // Not a Crossref DOI (e.g. DataCite: arXiv, Zenodo, datasets). OpenAlex indexes both.
            UriComponentsBuilder b = UriComponentsBuilder.fromUriString(OPENALEX + "/works/doi:" + doi.toLowerCase(java.util.Locale.ROOT))
                    .queryParam("select", "doi,display_name,authorships,publication_year,primary_location,cited_by_count,is_retracted,abstract_inverted_index");
            if (!openAlexKey.isEmpty()) {
                b.queryParam("api_key", openAlexKey);
            }
            JsonNode w = get(b.encode().build().toUri(), true);
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
                .queryParam("select", "DOI,title,author,issued,container-title,publisher,is-referenced-by-count,updated-by")
                .encode().build().toUri();
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

    /** Adds OpenAlex's retraction flag, abstract and citation count to a Crossref record. */
    private ScholarlyWork enrich(ScholarlyWork work) {
        if (work.doi() == null) {
            return work;
        }
        try {
            UriComponentsBuilder b = UriComponentsBuilder.fromUriString(OPENALEX + "/works/doi:" + work.doi())
                    .queryParam("select", "is_retracted,abstract_inverted_index,cited_by_count,primary_location");
            if (!openAlexKey.isEmpty()) {
                b.queryParam("api_key", openAlexKey);
            }
            JsonNode w = get(b.encode().build().toUri(), true);
            if (w == null) {
                return work;
            }
            return work.withIndexData(w.path("is_retracted").asBoolean(false), abstractFrom(w.path("abstract_inverted_index")),
                    w.path("cited_by_count").isNumber() ? w.path("cited_by_count").asInt() : null,
                    emptyToNull(w.path("primary_location").path("source").path("display_name").asString("")));
        } catch (ScholarlyIndexUnavailableException e) {
            log.warn("OpenAlex lookup failed: {}", e.getMessage());
            return work; // Crossref already confirmed the work exists
        }
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
        return new ScholarlyWork(
                emptyToNull(m.path("DOI").asString("").toLowerCase(java.util.Locale.ROOT)),
                emptyToNull(m.path("title").path(0).asString("")),
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

    /** DOIs contain '/', '<', '(' etc.; each part is percent-encoded but the prefix/suffix slash kept. */
    static String encodeDoi(String doi) {
        return java.net.URLEncoder.encode(doi, java.nio.charset.StandardCharsets.UTF_8).replace("+", "%20").replace("%2F", "/");
    }

    private static String emptyToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
