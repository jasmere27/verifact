package com.ai.agent.verifact.research;

import com.ai.agent.verifact.common.ApiException;
import com.ai.agent.verifact.common.Retention;
import com.ai.agent.verifact.research.ResearchProject.GapNote;
import com.ai.agent.verifact.research.ResearchProject.LibraryItem;
import com.ai.agent.verifact.research.ResearchProject.LinkNote;
import com.ai.agent.verifact.research.ResearchProject.LinkSuggestion;
import com.ai.agent.verifact.research.ResearchProject.Question;
import com.ai.agent.verifact.research.ResearchProject.ReadingStatus;
import com.ai.agent.verifact.research.ResearchWorkspace.Folder;
import com.ai.agent.verifact.research.ResearchWorkspace.SavedSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.UUID;

import static com.ai.agent.verifact.research.ResearchWorkspaceStore.cap;
import static com.ai.agent.verifact.research.ResearchWorkspaceStore.country;
import static com.ai.agent.verifact.research.ResearchWorkspaceStore.topic;

/**
 * Capstone projects (ADR-21). Every operation checks the owner: someone else's project is "not found". Sources
 * enter the library only through {@link SourceVerifier} (a fresh index lookup), so their details can't be forged;
 * everything else is the student's own text, capped in size. Projects are deleted 12 months after their last
 * change, or with the account (FK cascade).
 */
@Component
public class ResearchProjectStore {

    private static final Logger log = LoggerFactory.getLogger(ResearchProjectStore.class);
    private static final SecureRandom RANDOM = new SecureRandom();

    static final int MAX_PROJECTS = 20;
    static final int MAX_QUESTIONS = 10;
    static final int MAX_QUESTION_CHARS = 400;
    static final int MAX_HYPOTHESIS_CHARS = 600;
    static final int MAX_GAPS = 20;
    static final int MAX_GAP_CHARS = 1_500;
    static final int MAX_LIBRARY = 300;
    static final int MAX_ITEM_TEXT = 3_000;
    static final int MAX_FILES = 20;
    static final int MAX_LABEL = 80;
    static final int MAX_SUGGESTIONS = 40;
    static final int MAX_DISMISSED = 1_000;

    private final ResearchProjectRepository repository;
    private final ProjectFileRepository files;
    private final SourceVerifier verifier;
    private final JsonMapper jsonMapper;
    private final Clock clock;

    public ResearchProjectStore(ResearchProjectRepository repository, ProjectFileRepository files, SourceVerifier verifier,
                                JsonMapper jsonMapper, Clock clock) {
        this.repository = repository;
        this.files = files;
        this.verifier = verifier;
        this.jsonMapper = jsonMapper;
        this.clock = clock;
    }

    /** {@code hypothesis}: null keeps the current one, blank clears it. */
    public record QuestionInput(String id, String text, String hypothesis) {

        public QuestionInput(String id, String text) {
            this(id, text, null);
        }
    }

    public record GapInput(String id, String statement, List<String> sourceKeys) {}

    /** Null fields are left unchanged; lists replace the current ones. */
    public record Update(String title, String field, String country, String notes, List<QuestionInput> questions, List<GapInput> gaps) {}

    public record NewItem(String key, Folder folder, String relevance, String relevanceQuote, Discovery.Stance stance, String questionId) {}

    /** The student's edits to one library item; null fields are left unchanged. */
    public record ItemUpdate(String key, Folder folder, ReadingStatus status, String note, String keyFindings, String method,
                             List<String> questionIds) {}

    public List<ResearchProject.Summary> list(UUID owner) {
        int year = year();
        return repository.findTop50ByOwnerIdOrderByUpdatedAtDesc(owner).stream().map(r -> {
            ProjectData d = data(r);
            return new ResearchProject.Summary(r.getId(), d.title(), r.getUpdatedAt().toInstant(), deletesAt(r),
                    d.library().size(), d.questions().size(), ProjectAdvisor.progress(d, summaries(r.getId()), year).percent());
        }).toList();
    }

    public ResearchProject create(UUID owner, String title, String field, String country) {
        if (repository.countByOwnerId(owner) >= MAX_PROJECTS) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "You already have " + MAX_PROJECTS + " projects. Delete one you no longer need first.");
        }
        ProjectData d = new ProjectData(topic(title), cap(field, 120), country(country), List.of(), List.of(), List.of(), null, null, null);
        ResearchProjectRecord r = new ResearchProjectRecord(UUID.randomUUID(), owner, now(), json(d));
        repository.save(r);
        return view(r, d);
    }

    /** A copy of a quick workspace (its sources, notes, draft and insights) as a new project. */
    public ResearchProject importWorkspace(UUID owner, ResearchWorkspace ws) {
        ResearchProject created = create(owner, ws.topic(), ws.field(), ws.country());
        ResearchProjectRecord r = owned(owner, created.id());
        List<LibraryItem> library = ws.sources() == null ? List.of() : ws.sources().stream().limit(MAX_LIBRARY)
                .map(s -> new LibraryItem(s.key(), s.folder(), s.source(), ReadingStatus.TO_READ, s.studentNote(), null, null, List.of(), s.savedAt()))
                .toList();
        ProjectData d = new ProjectData(ws.topic(), ws.field(), ws.country(), List.of(), library, List.of(), ws.notes(), ws.draft(), ws.insights());
        return save(r, d);
    }

    @Transactional
    public ResearchProject get(UUID owner, UUID id) {
        ResearchProjectRecord r = owned(owner, id);
        ProjectData d = data(r);
        if (d.draft() != null) {
            // Projects from before files (phase 1) had one draft: it becomes the first file.
            Draft old = d.draft();
            saveFile(r.getId(), new ProjectFile(UUID.randomUUID(), ProjectFile.Kind.DRAFT, "Draft", old.fileName(), old.kind(), old.pages(),
                    old.chars(), old.truncated(), old.uploadedAt(), null, old, null));
            return save(r, d.withDraft(null));
        }
        return view(r, d);
    }

    // ---------------------------------------------------------------- files (ADR-23)

    /** Refuses before the upload is read or analysed. */
    public void requireRoomForFile(UUID owner, UUID id) {
        owned(owner, id);
        if (files.countByProjectId(id) >= MAX_FILES) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "This project already has " + MAX_FILES + " files. Delete one you no longer need first.");
        }
    }

    @Transactional
    public ResearchProject addFile(UUID owner, UUID id, ProjectFile.Kind kind, String label, String fileName, DocumentExtractor.Extracted doc,
                                   Draft draft, PaperAnalysis paper) {
        ResearchProjectRecord r = owned(owner, id);
        ProjectFile f = new ProjectFile(UUID.randomUUID(), kind, cap(label, MAX_LABEL), cap(fileName, 200), doc.kind(), doc.pages(),
                doc.text().length(), doc.truncated(), clock.instant(), kind == ProjectFile.Kind.PAPER ? doc.text() : null, draft, paper);
        saveFile(r.getId(), f);
        return save(r, data(r)); // touch: the project changed
    }

    public ProjectFile file(UUID owner, UUID id, UUID fileId) {
        owned(owner, id);
        return jsonMapper.readValue(fileRecord(id, fileId).getDetailJson(), ProjectFile.class);
    }

    @Transactional
    public ResearchProject relabelFile(UUID owner, UUID id, UUID fileId, String label) {
        ResearchProjectRecord r = owned(owner, id);
        ProjectFileRecord fr = fileRecord(id, fileId);
        ProjectFile f = jsonMapper.readValue(fr.getDetailJson(), ProjectFile.class);
        ProjectFile next = new ProjectFile(f.id(), f.kind(), cap(label, MAX_LABEL), f.fileName(), f.docKind(), f.pages(), f.chars(),
                f.truncated(), f.uploadedAt(), f.text(), f.draft(), f.paper());
        fr.relabel(next.label(), jsonMapper.writeValueAsString(next.summary()), jsonMapper.writeValueAsString(next));
        files.save(fr);
        return save(r, data(r));
    }

    @Transactional
    public ResearchProject deleteFile(UUID owner, UUID id, UUID fileId) {
        ResearchProjectRecord r = owned(owner, id);
        files.delete(fileRecord(id, fileId));
        return save(r, data(r));
    }

    private void saveFile(UUID projectId, ProjectFile f) {
        files.save(new ProjectFileRecord(f.id(), projectId, f.kind().name(), f.label(), f.fileName(), f.uploadedAt().atOffset(ZoneOffset.UTC),
                jsonMapper.writeValueAsString(f.summary()), jsonMapper.writeValueAsString(f)));
    }

    private ProjectFileRecord fileRecord(UUID projectId, UUID fileId) {
        return files.findByIdAndProjectId(fileId, projectId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "That file isn't in this project (it may have been deleted)."));
    }

    private List<ProjectFile.Summary> summaries(UUID projectId) {
        return files.summaries(projectId).stream().map(j -> jsonMapper.readValue(j, ProjectFile.Summary.class)).toList();
    }

    @Transactional
    public ResearchProject update(UUID owner, UUID id, Update u) {
        ResearchProjectRecord r = owned(owner, id);
        ProjectData d = data(r);
        List<Question> questions = u.questions() == null ? d.questions() : questions(u.questions(), d.questions());
        Set<String> questionIds = ids(questions);
        // A reworded question (or expected answer) makes the AI's readings for it stale; the student's links stay.
        Map<String, Question> before = d.questions().stream().collect(Collectors.toMap(Question::id, q -> q));
        Set<String> unchanged = questions.stream().filter(q -> q.equals(before.get(q.id()))).map(Question::id).collect(Collectors.toSet());
        // Removed questions disappear from the library links too.
        List<LibraryItem> library = d.library().stream()
                .map(i -> withQuestions(i, i.questionIds() == null ? List.of() : i.questionIds().stream().filter(questionIds::contains).toList(),
                        unchanged::contains))
                .toList();
        List<LinkSuggestion> suggestions = d.suggestions().stream().filter(x -> unchanged.contains(x.questionId())).toList();
        List<String> dismissed = d.dismissed().stream().filter(x -> unchanged.contains(x.substring(x.indexOf(' ') + 1))).toList();
        Set<String> keys = new HashSet<>(library.stream().map(LibraryItem::key).toList());
        List<GapNote> gaps = u.gaps() == null ? d.gaps() : gaps(u.gaps(), d.gaps(), keys);
        ProjectData next = new ProjectData(
                u.title() == null ? d.title() : topic(u.title()),
                u.field() == null ? d.field() : cap(u.field(), 120),
                u.country() == null ? d.country() : (u.country().isBlank() ? null : country(u.country())),
                questions, library, gaps,
                u.notes() == null ? d.notes() : cap(u.notes(), ResearchWorkspaceStore.MAX_NOTES_CHARS),
                d.draft(), d.insights(), suggestions, dismissed);
        return save(r, next);
    }

    /** Adds a verified source to the library (re-fetched from the index), optionally linked to a question. */
    @Transactional
    public ResearchProject addSource(UUID owner, UUID id, NewItem item) {
        ResearchProjectRecord r = owned(owner, id);
        ProjectData d = data(r);
        if (item == null || item.key() == null || item.key().isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Missing source.");
        }
        if (d.library().stream().anyMatch(i -> i.key().equalsIgnoreCase(item.key().trim()))) {
            return view(r, d); // already saved
        }
        if (d.library().size() >= MAX_LIBRARY) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "This project already has " + MAX_LIBRARY + " sources.");
        }
        Discovery.FoundSource found = verifier.verify(item.key(), item.relevance(), item.relevanceQuote(), item.stance(), d.title(), d.country());
        List<String> links = item.questionId() != null && ids(d.questions()).contains(item.questionId()) ? List.of(item.questionId()) : List.of();
        List<LibraryItem> library = new ArrayList<>(d.library());
        library.add(new LibraryItem(found.key(), item.folder() == null ? Folder.OTHER : item.folder(), found, ReadingStatus.TO_READ,
                null, null, null, links, clock.instant()));
        return save(r, d.withLibrary(library));
    }

    @Transactional
    public ResearchProject updateItem(UUID owner, UUID id, ItemUpdate u) {
        ResearchProjectRecord r = owned(owner, id);
        ProjectData d = data(r);
        if (u == null || u.key() == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Missing source.");
        }
        Set<String> questionIds = ids(d.questions());
        LibraryItem updated = null;
        List<LibraryItem> library = new ArrayList<>();
        for (LibraryItem i : d.library()) {
            if (i.key().equalsIgnoreCase(u.key())) {
                List<String> links = u.questionIds() == null ? i.questionIds() : u.questionIds().stream().filter(questionIds::contains).distinct().toList();
                i = new LibraryItem(i.key(), u.folder() == null ? i.folder() : u.folder(), i.source(),
                        u.status() == null ? i.status() : u.status(),
                        u.note() == null ? i.note() : cap(u.note(), MAX_ITEM_TEXT),
                        u.keyFindings() == null ? i.keyFindings() : cap(u.keyFindings(), MAX_ITEM_TEXT),
                        u.method() == null ? i.method() : cap(u.method(), MAX_ITEM_TEXT),
                        links, i.savedAt(), i.linkNotes().stream().filter(n -> links.contains(n.questionId())).toList());
                updated = i;
            }
            library.add(i);
        }
        if (updated == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "That source isn't in this project.");
        }
        // Linking it by hand settles any suggestion for the same pair.
        LibraryItem item = updated;
        List<LinkSuggestion> suggestions = d.suggestions().stream()
                .filter(x -> !(x.key().equalsIgnoreCase(item.key()) && item.questionIds().contains(x.questionId()))).toList();
        return save(r, d.withLinks(library, suggestions, d.dismissed()));
    }

    /** Removes a source; gap statements stop citing it. */
    @Transactional
    public ResearchProject removeItem(UUID owner, UUID id, String key) {
        ResearchProjectRecord r = owned(owner, id);
        ProjectData d = data(r);
        List<LibraryItem> library = d.library().stream().filter(i -> !i.key().equalsIgnoreCase(key)).toList();
        List<GapNote> gaps = d.gaps().stream()
                .map(g -> new GapNote(g.id(), g.statement(), g.sourceKeys().stream().filter(k -> !k.equalsIgnoreCase(key)).toList()))
                .toList();
        List<LinkSuggestion> suggestions = d.suggestions().stream().filter(x -> !x.key().equalsIgnoreCase(key)).toList();
        String prefix = key.toLowerCase(java.util.Locale.ROOT) + " ";
        List<String> dismissed = d.dismissed().stream().filter(x -> !x.startsWith(prefix)).toList();
        return save(r, new ProjectData(d.title(), d.field(), d.country(), d.questions(), library, gaps, d.notes(), d.draft(), d.insights(),
                suggestions, dismissed));
    }

    // ---------------------------------------------------------------- source ↔ question links (ADR-24)

    /** The stored project, for the link suggester. */
    ProjectData read(UUID owner, UUID id) {
        return data(owned(owner, id));
    }

    /**
     * Replaces the pending suggestions with a new run's. Checked again against the project as it is now (the run took
     * a while): the source and question must still exist, the pair mustn't be linked already or rejected before.
     */
    @Transactional
    public ResearchProject setSuggestions(UUID owner, UUID id, List<LinkSuggestion> found) {
        ResearchProjectRecord r = owned(owner, id);
        ProjectData d = data(r);
        Set<String> questionIds = ids(d.questions());
        Set<String> taken = new HashSet<>(d.dismissed());
        for (LibraryItem i : d.library()) {
            i.questionIds().forEach(q -> taken.add(ProjectData.pair(i.key(), q)));
        }
        Set<String> keys = d.library().stream().map(i -> i.key().toLowerCase(java.util.Locale.ROOT)).collect(Collectors.toSet());
        List<LinkSuggestion> next = found.stream()
                .filter(x -> questionIds.contains(x.questionId()) && keys.contains(x.key().toLowerCase(java.util.Locale.ROOT)))
                .filter(x -> taken.add(ProjectData.pair(x.key(), x.questionId())))
                .limit(MAX_SUGGESTIONS)
                .toList();
        return save(r, d.withLinks(d.library(), next, d.dismissed()));
    }

    /** Accept (link it, keeping the AI's reading with its quote) or reject (never suggested again) one suggestion. */
    @Transactional
    public ResearchProject reviewLink(UUID owner, UUID id, String key, String questionId, boolean accept) {
        ResearchProjectRecord r = owned(owner, id);
        ProjectData d = data(r);
        LinkSuggestion s = d.suggestions().stream()
                .filter(x -> x.key().equalsIgnoreCase(key == null ? "" : key) && x.questionId().equals(questionId))
                .findFirst()
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "That suggestion is no longer waiting for review."));
        List<LinkSuggestion> rest = d.suggestions().stream().filter(x -> x != s).toList();
        if (!accept) {
            List<String> dismissed = new ArrayList<>(d.dismissed());
            dismissed.add(ProjectData.pair(s.key(), s.questionId()));
            return save(r, d.withLinks(d.library(), rest, dismissed.size() > MAX_DISMISSED
                    ? dismissed.subList(dismissed.size() - MAX_DISMISSED, dismissed.size()) : dismissed));
        }
        List<LibraryItem> library = d.library().stream().map(i -> {
            if (!i.key().equalsIgnoreCase(s.key())) {
                return i;
            }
            List<String> links = new ArrayList<>(i.questionIds());
            if (!links.contains(s.questionId())) {
                links.add(s.questionId());
            }
            List<LinkNote> notes = new ArrayList<>(i.linkNotes().stream().filter(n -> !n.questionId().equals(s.questionId())).toList());
            notes.add(new LinkNote(s.questionId(), s.role(), s.stance(), s.how(), s.quote()));
            return new LibraryItem(i.key(), i.folder(), i.source(), i.status(), i.note(), i.keyFindings(), i.method(), links, i.savedAt(), notes);
        }).toList();
        return save(r, d.withLinks(library, rest, d.dismissed()));
    }

    @Transactional
    public ResearchProject setInsights(UUID owner, UUID id, Insights insights) {
        ResearchProjectRecord r = owned(owner, id);
        return save(r, data(r).withInsights(insights));
    }

    /** The project in the shape the insights service reads (topic, field, country, sources with notes). */
    public ResearchWorkspace asWorkspace(UUID owner, UUID id) {
        ResearchProjectRecord r = owned(owner, id);
        ProjectData d = data(r);
        List<SavedSource> sources = d.library().stream()
                .map(i -> new SavedSource(i.key(), i.folder(), i.source(), i.note(), i.savedAt())).toList();
        return new ResearchWorkspace(r.getId(), r.getCreatedAt().toInstant(), r.getUpdatedAt().toInstant(), deletesAt(r), d.title(),
                d.field(), d.country(), sources, d.notes(), d.draft(), d.insights());
    }

    /** Checks the owner before expensive work (reading a file, calling the model). */
    public void requireOwner(UUID owner, UUID id) {
        owned(owner, id);
    }

    @Transactional
    public void delete(UUID owner, UUID id) {
        repository.delete(owned(owner, id));
    }

    /** Daily: projects unchanged for 12 months are deleted. */
    @Scheduled(cron = "${app.retention.cleanup-cron:0 27 3 * * *}")
    public void deleteExpired() {
        int n = repository.deleteUpdatedBefore(clock.instant().minus(Retention.PROJECT_PERIOD).atOffset(ZoneOffset.UTC));
        if (n > 0) {
            log.info("Deleted {} research projects past retention", n);
        }
    }

    // ---------------------------------------------------------------- helpers

    private ResearchProjectRecord owned(UUID owner, UUID id) {
        return repository.findByIdAndOwnerId(id, owner).orElseThrow(ResearchProjectStore::notFound);
    }

    static ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "That project doesn't exist, was deleted, or isn't yours.");
    }

    private ResearchProject save(ResearchProjectRecord r, ProjectData d) {
        r.update(json(d), now());
        repository.save(r);
        return view(r, d);
    }

    private ResearchProject view(ResearchProjectRecord r, ProjectData d) {
        int year = year();
        List<ProjectFile.Summary> fileSummaries = summaries(r.getId());
        return new ResearchProject(r.getId(), r.getCreatedAt().toInstant(), r.getUpdatedAt().toInstant(), deletesAt(r), d.title(), d.field(),
                d.country(), d.questions(), d.library(), d.gaps(), d.notes(), d.draft(), d.insights(), fileSummaries,
                d.suggestions(), ProjectAdvisor.progress(d, fileSummaries, year), ProjectAdvisor.nextSteps(d, fileSummaries, year));
    }

    private static Instant deletesAt(ResearchProjectRecord r) {
        return r.getUpdatedAt().toInstant().plus(Retention.PROJECT_PERIOD);
    }

    private ProjectData data(ResearchProjectRecord r) {
        return jsonMapper.readValue(r.getDataJson(), ProjectData.class);
    }

    private String json(ProjectData d) {
        return jsonMapper.writeValueAsString(d);
    }

    private OffsetDateTime now() {
        return clock.instant().atOffset(ZoneOffset.UTC);
    }

    private int year() {
        return clock.instant().atOffset(ZoneOffset.UTC).getYear();
    }

    private static List<Question> questions(List<QuestionInput> input, List<Question> current) {
        Set<String> existing = ids(current);
        List<Question> out = new ArrayList<>();
        Set<String> used = new HashSet<>();
        for (QuestionInput q : input) {
            String text = q == null ? null : cap(q.text(), MAX_QUESTION_CHARS);
            if (text == null) {
                continue;
            }
            if (out.size() >= MAX_QUESTIONS) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "Keep it to " + MAX_QUESTIONS + " research questions or fewer.");
            }
            String id = q.id() != null && existing.contains(q.id()) && used.add(q.id()) ? q.id() : newId(used);
            Question old = current.stream().filter(x -> x.id().equals(id)).findFirst().orElse(null);
            String hypothesis = q.hypothesis() == null ? (old == null ? null : old.hypothesis()) : cap(q.hypothesis(), MAX_HYPOTHESIS_CHARS);
            out.add(new Question(id, text, hypothesis));
        }
        return out;
    }

    private static List<GapNote> gaps(List<GapInput> input, List<GapNote> current, Set<String> libraryKeys) {
        Set<String> existing = new HashSet<>(current.stream().map(GapNote::id).toList());
        List<GapNote> out = new ArrayList<>();
        Set<String> used = new HashSet<>();
        for (GapInput g : input) {
            String text = g == null ? null : cap(g.statement(), MAX_GAP_CHARS);
            if (text == null) {
                continue;
            }
            if (out.size() >= MAX_GAPS) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "Keep it to " + MAX_GAPS + " gap statements or fewer.");
            }
            String id = g.id() != null && existing.contains(g.id()) && used.add(g.id()) ? g.id() : newId(used);
            List<String> keys = g.sourceKeys() == null ? List.of() : g.sourceKeys().stream().filter(libraryKeys::contains).distinct().toList();
            out.add(new GapNote(id, text, keys));
        }
        return out;
    }

    /** {@code keepNotes}: questions whose AI readings are still current. */
    private static LibraryItem withQuestions(LibraryItem i, List<String> questionIds, Predicate<String> keepNotes) {
        return new LibraryItem(i.key(), i.folder(), i.source(), i.status(), i.note(), i.keyFindings(), i.method(), questionIds, i.savedAt(),
                i.linkNotes().stream().filter(n -> questionIds.contains(n.questionId()) && keepNotes.test(n.questionId())).toList());
    }

    private static Set<String> ids(List<Question> questions) {
        return new HashSet<>(questions.stream().map(Question::id).toList());
    }

    private static String newId(Set<String> used) {
        String id;
        do {
            id = (Long.toString(RANDOM.nextLong() & Long.MAX_VALUE, 36) + "00000000").substring(0, 8);
        } while (!used.add(id));
        return id;
    }
}
