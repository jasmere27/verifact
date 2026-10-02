package com.ai.agent.verifact.research;

import com.ai.agent.verifact.common.ApiException;
import com.ai.agent.verifact.common.EditTokens;
import com.ai.agent.verifact.research.ResearchWorkspace.Folder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Workspace rules: tokens, server-side re-verification of saved sources, retention. */
class ResearchWorkspaceStoreTest {

    private final ResearchWorkspaceRepository repository = mock(ResearchWorkspaceRepository.class);
    private final ResearchDiscoveryServiceTest.FakeIndex index = new ResearchDiscoveryServiceTest.FakeIndex() {
        @Override
        public Optional<DiscoveredWork> work(String key) {
            return "10.1/real".equals(key) ? Optional.of(ResearchDiscoveryServiceTest.work("10.1/real",
                    "Flipped classroom in Philippine senior high mathematics",
                    "The flipped classroom improved mathematics achievement of senior high students.", "PH")) : Optional.empty();
        }
    };
    private final Clock clock = Clock.fixed(Instant.parse("2026-09-30T12:00:00Z"), ZoneOffset.UTC);
    private final ResearchWorkspaceStore store = new ResearchWorkspaceStore(repository, new SourceVerifier(index), JsonMapper.builder().build(), clock);

    private ResearchWorkspaceRecord record;
    private String token;
    private UUID id;

    @BeforeEach
    void setUp() {
        ArgumentCaptor<ResearchWorkspaceRecord> saved = ArgumentCaptor.forClass(ResearchWorkspaceRecord.class);
        ResearchWorkspace.View v = store.create("Flipped classroom and mathematics achievement", "Education", "ph");
        verify(repository).save(saved.capture());
        record = saved.getValue();
        token = v.editToken();
        id = v.workspace().id();
        when(repository.findById(id)).thenReturn(Optional.of(record));
    }

    @Test
    void creatingStoresOnlyTheTokenHashAndSetsA90DayExpiry() {
        assertThat(record.getEditTokenHash()).isEqualTo(EditTokens.hash(token));
        assertThat(record.getExpiresAt().toInstant()).isEqualTo(Instant.parse("2026-12-29T12:00:00Z"));
        assertThat(store.find(id)).hasValueSatisfying(w -> assertThat(w.country()).isEqualTo("PH"));
    }

    @Test
    void savedSourcesAreRefetchedAndForgedDetailsOrQuotesAreIgnored() {
        ResearchWorkspace w = store.addSource(id, token, new ResearchWorkspaceStore.NewSource("10.1/real", Folder.RRS,
                "Same intervention in a Philippine setting.", "improved mathematics achievement of senior high students", null));

        assertThat(w.sources()).singleElement().satisfies(s -> {
            assertThat(s.source().title()).isEqualTo("Flipped classroom in Philippine senior high mathematics");
            assertThat(s.source().local()).isTrue();
            assertThat(s.source().relevanceQuote()).isEqualTo("improved mathematics achievement of senior high students");
            assertThat(s.folder()).isEqualTo(Folder.RRS);
        });

        // A made-up quote is dropped; the source itself still comes from the index.
        store.update(id, token, new ResearchWorkspaceStore.Update(null, null, null, null, List.of()));
        w = store.addSource(id, token, new ResearchWorkspaceStore.NewSource("10.1/real", Folder.RRS, "It proves everything.",
                "flipped classrooms always double scores everywhere", null));
        assertThat(w.sources().get(0).source().relevance()).isNull();

        // Not in the index → not saved.
        assertThatThrownBy(() -> store.addSource(id, token, new ResearchWorkspaceStore.NewSource("10.9/fabricated", Folder.RRL, null, null, null)))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void updatesCanOnlyKeepReorderOrRefileSavedSources() {
        store.addSource(id, token, new ResearchWorkspaceStore.NewSource("10.1/real", Folder.RRS, null, null, null));

        ResearchWorkspace w = store.update(id, token, new ResearchWorkspaceStore.Update("New title for the study", null, null, "My notes",
                List.of(new ResearchWorkspaceStore.SourceOrder("10.1/real", Folder.EVIDENCE, "Use in chapter 2"),
                        new ResearchWorkspaceStore.SourceOrder("10.9/injected", Folder.RRL, null))));

        assertThat(w.topic()).isEqualTo("New title for the study");
        assertThat(w.notes()).isEqualTo("My notes");
        assertThat(w.sources()).singleElement().satisfies(s -> {
            assertThat(s.folder()).isEqualTo(Folder.EVIDENCE);
            assertThat(s.studentNote()).isEqualTo("Use in chapter 2");
        });
    }

    @Test
    void onlyTheTokenHolderCanChangeOrDelete() {
        assertThatThrownBy(() -> store.update(id, "wrong", new ResearchWorkspaceStore.Update("x title", null, null, null, null)))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
        assertThatThrownBy(() -> store.delete(id, null)).isInstanceOf(ApiException.class);
        store.delete(id, token);
        verify(repository).delete(record);
    }

    @Test
    void expiredWorkspacesAreGoneAndCleanedUp() {
        ResearchWorkspaceStore later = new ResearchWorkspaceStore(repository, new SourceVerifier(index), JsonMapper.builder().build(),
                Clock.fixed(Instant.parse("2027-01-01T00:00:00Z"), ZoneOffset.UTC));
        assertThat(later.find(id)).isEmpty();
        assertThatThrownBy(() -> later.update(id, token, new ResearchWorkspaceStore.Update(null, null, null, "x", null)))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.NOT_FOUND));

        later.deleteExpired();
        verify(repository).deleteExpired(any());
    }
}
