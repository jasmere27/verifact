package com.ai.agent.verifact.research;

import com.ai.agent.verifact.common.ApiException;
import com.ai.agent.verifact.common.EditTokens;
import com.ai.agent.verifact.evidence.Grounding;
import com.ai.agent.verifact.research.ResearchWorkspace.Folder;
import com.ai.agent.verifact.research.ResearchWorkspace.SavedSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Student workspaces: create (returns the edit token once), read by id, change with the token, and
 * automatic deletion 90 days after the last change. Saved sources are re-fetched from the index by
 * key, so a client can't plant a fabricated "verified" source.
 */
@Component
public class ResearchWorkspaceStore {

    private static final Logger log = LoggerFactory.getLogger(ResearchWorkspaceStore.class);

    static final Duration RETENTION = Duration.ofDays(90);
    static final int MAX_SOURCES = 200;
    static final int MAX_NOTES_CHARS = 20_000;
    static final int MAX_NOTE_CHARS = 1_000;

    /** What's stored as JSON (id and timestamps live in columns). */
    record Data(String topic, String field, String country, List<SavedSource> sources, String notes) {}

    public record SourceOrder(String key, Folder folder, String studentNote) {}

    public record Update(String topic, String field, String country, String notes, List<SourceOrder> sources) {}

    public record NewSource(String key, Folder folder, String relevance, String relevanceQuote, Discovery.Stance stance) {}

    private final ResearchWorkspaceRepository repository;
    private final ScholarlyIndex index;
    private final JsonMapper jsonMapper;
    private final Clock clock;

    public ResearchWorkspaceStore(ResearchWorkspaceRepository repository, ScholarlyIndex index, JsonMapper jsonMapper, Clock clock) {
        this.repository = repository;
        this.index = index;
        this.jsonMapper = jsonMapper;
        this.clock = clock;
    }

    public ResearchWorkspace.View create(String topic, String field, String country) {
        String token = EditTokens.newToken();
        Instant now = clock.instant();
        Data data = new Data(topic(topic), cap(field, 120), country(country), List.of(), null);
        UUID id = UUID.randomUUID();
        repository.save(new ResearchWorkspaceRecord(id, now.atOffset(ZoneOffset.UTC), now.plus(RETENTION).atOffset(ZoneOffset.UTC),
                jsonMapper.writeValueAsString(data), EditTokens.hash(token)));
        return new ResearchWorkspace.View(view(id, now, now, now.plus(RETENTION), data), token);
    }

    public Optional<ResearchWorkspace> find(UUID id) {
        return repository.findById(id).filter(r -> r.getExpiresAt().toInstant().isAfter(clock.instant())).map(this::view);
    }

    /** Topic, folders, order, notes; only sources already saved can be kept (additions go through {@link #addSource}). */
    @Transactional
    public ResearchWorkspace update(UUID id, String token, Update update) {
        ResearchWorkspaceRecord record = owned(id, token);
        Data data = data(record);
        Map<String, SavedSource> existing = new LinkedHashMap<>();
        data.sources().forEach(s -> existing.put(s.key(), s));
        List<SavedSource> kept = new ArrayList<>();
        if (update.sources() != null) {
            for (SourceOrder o : update.sources()) {
                SavedSource s = o == null ? null : existing.remove(o.key());
                if (s != null) {
                    kept.add(new SavedSource(s.key(), o.folder() == null ? s.folder() : o.folder(), s.source(),
                            cap(o.studentNote(), MAX_NOTE_CHARS), s.savedAt()));
                }
            }
        } else {
            kept.addAll(existing.values());
        }
        Data next = new Data(update.topic() == null ? data.topic() : topic(update.topic()),
                update.field() == null ? data.field() : cap(update.field(), 120),
                update.country() == null ? data.country() : country(update.country()),
                kept, update.notes() == null ? data.notes() : cap(update.notes(), MAX_NOTES_CHARS));
        return save(record, next);
    }

    /**
     * Saves a source by its DOI or OpenAlex id. The details come from a fresh index lookup; the
     * "why it's relevant" note is kept only if its quote is verbatim in that source's abstract.
     */
    @Transactional
    public ResearchWorkspace addSource(UUID id, String token, NewSource s) {
        ResearchWorkspaceRecord record = owned(id, token);
        Data data = data(record);
        if (s == null || s.key() == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Missing source.");
        }
        if (data.sources().stream().anyMatch(x -> x.key().equalsIgnoreCase(s.key().trim()))) {
            return view(record); // already saved
        }
        if (data.sources().size() >= MAX_SOURCES) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "This workspace already has " + MAX_SOURCES + " sources.");
        }
        DiscoveredWork work;
        try {
            work = index.work(s.key().trim()).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                    "That source couldn't be found in OpenAlex, so it can't be saved as verified."));
        } catch (ScholarlyIndex.ScholarlyIndexUnavailableException e) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "The research database is unavailable; try saving again shortly.", e);
        }
        String abs = work.work().abstractText();
        String quote = abs == null ? null : ResearchCheckService.verbatim(s.relevanceQuote(), abs, data.topic());
        String why = quote == null ? null : cap(s.relevance(), 300);
        if (why != null && !Grounding.supported(why, Grounding.material(data.topic(), quote, work.work().title()))) {
            why = null;
            quote = null;
        }
        Discovery.FoundSource found = ResearchDiscoveryService.toSource(work, why, quote, quote == null ? null : s.stance(), data.country());
        List<SavedSource> sources = new ArrayList<>(data.sources());
        sources.add(new SavedSource(found.key(), s.folder() == null ? Folder.OTHER : s.folder(), found, null, clock.instant()));
        return save(record, new Data(data.topic(), data.field(), data.country(), sources, data.notes()));
    }

    @Transactional
    public void delete(UUID id, String token) {
        repository.delete(owned(id, token));
    }

    /** Daily: workspaces 90 days past their last change are deleted. */
    @Scheduled(cron = "${app.research.workspace-cleanup-cron:0 17 3 * * *}")
    public void deleteExpired() {
        int n = repository.deleteExpired(clock.instant().atOffset(ZoneOffset.UTC));
        if (n > 0) {
            log.info("Deleted {} expired research workspaces", n);
        }
    }

    private ResearchWorkspace save(ResearchWorkspaceRecord record, Data data) {
        Instant now = clock.instant();
        record.update(jsonMapper.writeValueAsString(data), now.atOffset(ZoneOffset.UTC), now.plus(RETENTION).atOffset(ZoneOffset.UTC));
        repository.save(record);
        return view(record);
    }

    private ResearchWorkspaceRecord owned(UUID id, String token) {
        ResearchWorkspaceRecord record = repository.findById(id)
                .filter(r -> r.getExpiresAt().toInstant().isAfter(clock.instant()))
                .orElseThrow(ResearchWorkspaceStore::notFound);
        if (!EditTokens.matches(token, record.getEditTokenHash())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Only the person who created this workspace can change it.");
        }
        return record;
    }

    private Data data(ResearchWorkspaceRecord r) {
        Data d = jsonMapper.readValue(r.getDataJson(), Data.class);
        return new Data(d.topic(), d.field(), d.country(), d.sources() == null ? List.of() : d.sources(), d.notes());
    }

    private ResearchWorkspace view(ResearchWorkspaceRecord r) {
        return view(r.getId(), r.getCreatedAt().toInstant(), r.getUpdatedAt().toInstant(), r.getExpiresAt().toInstant(), data(r));
    }

    private static ResearchWorkspace view(UUID id, Instant created, Instant updated, Instant expires, Data d) {
        return new ResearchWorkspace(id, created, updated, expires, d.topic(), d.field(), d.country(), d.sources(), d.notes());
    }

    static ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "That workspace doesn't exist, was deleted, or expired after 90 days without changes.");
    }

    private static String topic(String t) {
        String s = cap(t, 300);
        if (s == null || s.length() < 5) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Enter your research topic or title.");
        }
        return s;
    }

    private static String country(String c) {
        if (c == null || c.isBlank()) {
            return null;
        }
        String v = c.trim().toUpperCase(java.util.Locale.ROOT);
        if (!v.matches("[A-Z]{2}")) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Country must be a two-letter code, e.g. PH.");
        }
        return v;
    }

    private static String cap(String s, int max) {
        if (s == null || s.isBlank()) {
            return null;
        }
        String t = s.strip();
        return t.length() > max ? t.substring(0, max) : t;
    }
}
